-- V8: SLA engine (Phase 2, M15): targets per ticket type and priority, and one clock per ticket and metric.
--
-- Clocks count wall-clock time, 24/7. Business hours would change how a due time is computed, not
-- what is stored, so they can be added later without reshaping these tables.

CREATE TABLE sla_policies (
    id                 uuid        PRIMARY KEY,
    ticket_type        varchar(20) NOT NULL,
    priority           varchar(2)  NOT NULL,
    response_minutes   integer     NOT NULL,
    resolution_minutes integer     NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,

    CONSTRAINT sla_policies_type_priority_uk UNIQUE (ticket_type, priority),
    CONSTRAINT sla_policies_type_check CHECK (ticket_type IN ('INCIDENT', 'SERVICE_REQUEST')),
    CONSTRAINT sla_policies_priority_check CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT sla_policies_targets_check CHECK (
        response_minutes > 0 AND resolution_minutes > 0 AND response_minutes <= resolution_minutes)
);

-- Reference data the application needs in every environment, not demo data: every ticket must find
-- a policy for its type and priority. Admins can change the numbers later; the rows always exist.
INSERT INTO sla_policies (id, ticket_type, priority, response_minutes, resolution_minutes, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'INCIDENT',        'P1',   15,   240, now(), now()), -- 15 min / 4 h
    (gen_random_uuid(), 'INCIDENT',        'P2',   60,   480, now(), now()), -- 1 h / 8 h
    (gen_random_uuid(), 'INCIDENT',        'P3',  240,  4320, now(), now()), -- 4 h / 3 days
    (gen_random_uuid(), 'INCIDENT',        'P4',  480,  7200, now(), now()), -- 8 h / 5 days
    (gen_random_uuid(), 'SERVICE_REQUEST', 'P1',   60,  1440, now(), now()), -- 1 h / 1 day
    (gen_random_uuid(), 'SERVICE_REQUEST', 'P2',  240,  2880, now(), now()), -- 4 h / 2 days
    (gen_random_uuid(), 'SERVICE_REQUEST', 'P3',  480,  7200, now(), now()), -- 8 h / 5 days
    (gen_random_uuid(), 'SERVICE_REQUEST', 'P4', 1440, 14400, now(), now()); -- 1 day / 10 days

-- A clock stores the time counted in finished running stretches (elapsed_ms) plus, while it runs,
-- when the current stretch began. Pausing folds the stretch into elapsed_ms; resuming starts a new
-- one. due_at is derived (running_since + target - elapsed) but stored, so the breach job can find
-- overdue clocks with an index instead of computing every clock's deadline.
CREATE TABLE sla_clocks (
    id             uuid        PRIMARY KEY,
    work_item_id   uuid        NOT NULL REFERENCES work_items (id) ON DELETE CASCADE,
    metric         varchar(20) NOT NULL,
    target_minutes integer     NOT NULL,
    state          varchar(20) NOT NULL,
    elapsed_ms     bigint      NOT NULL DEFAULT 0,
    running_since  timestamptz,
    due_at         timestamptz,
    breached_at    timestamptz,
    completed_at   timestamptz,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    version        bigint      NOT NULL DEFAULT 0,

    CONSTRAINT sla_clocks_work_item_metric_uk UNIQUE (work_item_id, metric),
    CONSTRAINT sla_clocks_metric_check CHECK (metric IN ('RESPONSE', 'RESOLUTION')),
    CONSTRAINT sla_clocks_state_check CHECK (state IN ('RUNNING', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT sla_clocks_amounts_check CHECK (target_minutes > 0 AND elapsed_ms >= 0),
    -- Only a running clock has a current stretch and a deadline.
    CONSTRAINT sla_clocks_running_check CHECK (
        (state = 'RUNNING' AND running_since IS NOT NULL AND due_at IS NOT NULL)
        OR (state <> 'RUNNING' AND running_since IS NULL AND due_at IS NULL)),
    CONSTRAINT sla_clocks_completed_check CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL))
);

-- The breach job's question: running clocks, not yet breached, whose deadline has passed.
CREATE INDEX sla_clocks_breach_due_idx ON sla_clocks (due_at) WHERE state = 'RUNNING' AND breached_at IS NULL;
