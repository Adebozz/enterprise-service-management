-- V4: ticket categories, work items (incidents + service requests) and the audit trail.

-- ---------------------------------------------------------------------------------------------
-- categories (two levels: category -> subcategory)
-- ---------------------------------------------------------------------------------------------
-- Routing is data, not code: each top-level category names the team that receives its tickets;
-- a subcategory may override it or inherit the parent's team.
CREATE TABLE categories (
    id              uuid         PRIMARY KEY,
    code            varchar(50)  NOT NULL,
    name            varchar(100) NOT NULL,
    parent_id       uuid         REFERENCES categories (id),
    default_team_id uuid         REFERENCES teams (id),
    applies_to      varchar(20)  NOT NULL,
    active          boolean      NOT NULL DEFAULT true,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0,

    CONSTRAINT categories_code_uk UNIQUE (code),
    CONSTRAINT categories_code_format_check CHECK (code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT categories_applies_to_check CHECK (applies_to IN ('INCIDENT', 'SERVICE_REQUEST', 'ANY')),
    -- Every ticket must be routable: a top-level category always has a team.
    CONSTRAINT categories_top_level_team_check CHECK (parent_id IS NOT NULL OR default_team_id IS NOT NULL),
    CONSTRAINT categories_not_own_parent_check CHECK (parent_id <> id)
);

CREATE INDEX categories_parent_idx ON categories (parent_id);

-- ---------------------------------------------------------------------------------------------
-- reference number sequences: INC-000001, REQ-000001
-- ---------------------------------------------------------------------------------------------
-- nextval() is atomic and never hands the same value to two transactions, unlike
-- "SELECT max(...) + 1". Values consumed by rolled-back transactions leave gaps; that's acceptable
-- for ticket references.
CREATE SEQUENCE incident_ref_seq START 1;
CREATE SEQUENCE service_request_ref_seq START 1;

-- ---------------------------------------------------------------------------------------------
-- work_items: columns shared by every ticket type (JPA JOINED inheritance root)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE work_items (
    id                 uuid         PRIMARY KEY,
    reference          varchar(20)  NOT NULL,
    type               varchar(20)  NOT NULL,
    title              varchar(200) NOT NULL,
    description        text         NOT NULL,
    status             varchar(30)  NOT NULL,
    impact             varchar(10)  NOT NULL,
    urgency            varchar(10)  NOT NULL,
    priority           varchar(2)   NOT NULL,
    category_id        uuid         NOT NULL REFERENCES categories (id),
    subcategory_id     uuid         REFERENCES categories (id),
    requester_id       uuid         NOT NULL REFERENCES users (id),
    assigned_team_id   uuid         NOT NULL REFERENCES teams (id),
    assignee_id        uuid,
    first_responded_at timestamptz,
    resolved_at        timestamptz,
    closed_at          timestamptz,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,

    CONSTRAINT work_items_reference_uk UNIQUE (reference),
    CONSTRAINT work_items_reference_format_check CHECK (reference ~ '^(INC|REQ)-[0-9]{6,}$'),
    CONSTRAINT work_items_type_check CHECK (type IN ('INCIDENT', 'SERVICE_REQUEST')),
    -- Each type has its own lifecycle; the database refuses a status that doesn't belong to it.
    CONSTRAINT work_items_status_check CHECK (
        (type = 'INCIDENT' AND status IN
            ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'WAITING_FOR_USER', 'RESOLVED', 'CLOSED', 'CANCELLED'))
        OR
        (type = 'SERVICE_REQUEST' AND status IN
            ('SUBMITTED', 'APPROVAL_PENDING', 'APPROVED', 'REJECTED', 'IN_PROGRESS', 'FULFILLED', 'CLOSED', 'CANCELLED'))
    ),
    CONSTRAINT work_items_impact_check CHECK (impact IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT work_items_urgency_check CHECK (urgency IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT work_items_priority_check CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT work_items_title_not_blank_check CHECK (btrim(title) <> ''),
    -- The assignee must be a member of the assigned team. With MATCH SIMPLE (the default) a NULL
    -- assignee skips the check, so unassigned tickets are allowed.
    CONSTRAINT work_items_assignee_in_team_fk
        FOREIGN KEY (assigned_team_id, assignee_id) REFERENCES team_members (team_id, user_id)
);

-- "My tickets" for requesters, newest first.
CREATE INDEX work_items_requester_created_idx ON work_items (requester_id, created_at DESC);
-- Team queues and per-agent queues, filtered by status.
CREATE INDEX work_items_team_status_idx ON work_items (assigned_team_id, status);
CREATE INDEX work_items_assignee_status_idx ON work_items (assignee_id, status);
-- Reporting by creation date (Phase 3).
CREATE INDEX work_items_created_idx ON work_items (created_at);

-- ---------------------------------------------------------------------------------------------
-- type-specific extension tables (share the work item's primary key)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE incidents (
    work_item_id     uuid         PRIMARY KEY REFERENCES work_items (id) ON DELETE CASCADE,
    affected_service varchar(120),
    resolution_code  varchar(40),
    resolution_notes text,
    reopen_count     integer      NOT NULL DEFAULT 0,

    CONSTRAINT incidents_reopen_count_check CHECK (reopen_count >= 0)
);

CREATE TABLE service_requests (
    work_item_id      uuid PRIMARY KEY REFERENCES work_items (id) ON DELETE CASCADE,
    catalogue_item_id uuid, -- foreign key added with the service catalogue (Phase 2)
    fulfilment_notes  text
);

-- ---------------------------------------------------------------------------------------------
-- audit_events: append-only business audit trail
-- ---------------------------------------------------------------------------------------------
-- No foreign keys on purpose: the audit trail must outlive and never block anything it describes.
-- actor_id NULL means "system" (bootstrap, scheduler).
CREATE TABLE audit_events (
    id             uuid        PRIMARY KEY,
    occurred_at    timestamptz NOT NULL,
    actor_id       uuid,
    action         varchar(60) NOT NULL,
    entity_type    varchar(40) NOT NULL,
    entity_id      uuid        NOT NULL,
    old_value      jsonb,
    new_value      jsonb,
    metadata       jsonb,
    correlation_id varchar(64)
);

-- Timeline of one entity (ticket history, user history).
CREATE INDEX audit_events_entity_idx ON audit_events (entity_type, entity_id, occurred_at);
-- "What did this person do?"
CREATE INDEX audit_events_actor_idx ON audit_events (actor_id, occurred_at);

-- Append-only, enforced by the database rather than by convention: even code with a bug (or
-- someone with application credentials and psql) cannot rewrite history. TRUNCATE is a separate
-- privilege, controlled through database roles in production.
CREATE FUNCTION audit_events_reject_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    -- restrict_violation (23001) is an integrity error, so the application sees a
    -- DataIntegrityViolationException rather than a misleading "bad SQL grammar".
    RAISE EXCEPTION 'audit_events is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_reject_modification();
