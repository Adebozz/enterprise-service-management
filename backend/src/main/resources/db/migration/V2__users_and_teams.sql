-- V2: users, teams and team membership.

-- ---------------------------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------------------------
-- One role per user. Roles are strictly hierarchical (ADMIN > TEAM_LEAD > AGENT > REQUESTER), so
-- a user_roles join table would add joins and complexity without adding expressiveness.
-- Users are deactivated, never deleted: tickets, comments and audit events keep referencing them.
CREATE TABLE users (
    id            uuid         PRIMARY KEY,
    email         varchar(254) NOT NULL,
    display_name  varchar(120) NOT NULL,
    password_hash varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL,
    active        boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,

    CONSTRAINT users_role_check CHECK (role IN ('REQUESTER', 'AGENT', 'TEAM_LEAD', 'ADMIN')),
    -- The application normalises emails; the database refuses anything that isn't normalised.
    -- That makes the plain unique constraint below effectively case-insensitive.
    CONSTRAINT users_email_normalised_check CHECK (email = lower(btrim(email))),
    CONSTRAINT users_display_name_not_blank_check CHECK (btrim(display_name) <> ''),
    CONSTRAINT users_email_uk UNIQUE (email)
);

-- Admin "active admins" lookups and role filtering.
CREATE INDEX users_role_active_idx ON users (role, active);

-- ---------------------------------------------------------------------------------------------
-- teams
-- ---------------------------------------------------------------------------------------------
CREATE TABLE teams (
    id          uuid         PRIMARY KEY,
    name        varchar(100) NOT NULL,
    description varchar(500),
    active      boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,

    CONSTRAINT teams_name_not_blank_check CHECK (btrim(name) <> '')
);

-- "Network Team" and "network team" are the same team. Display casing is preserved.
CREATE UNIQUE INDEX teams_name_uk ON teams (lower(name));

-- ---------------------------------------------------------------------------------------------
-- team_members
-- ---------------------------------------------------------------------------------------------
-- A user can belong to several teams. The composite primary key (team_id, user_id) will be the
-- target of a composite foreign key from work_items (M5), so the database itself rejects an
-- assignee who is not a member of the assigned team.
CREATE TABLE team_members (
    team_id   uuid        NOT NULL REFERENCES teams (id),
    user_id   uuid        NOT NULL REFERENCES users (id),
    joined_at timestamptz NOT NULL,

    CONSTRAINT team_members_pk PRIMARY KEY (team_id, user_id)
);

-- The PK index serves "members of team X"; this one serves "teams of user Y".
CREATE INDEX team_members_user_idx ON team_members (user_id);
