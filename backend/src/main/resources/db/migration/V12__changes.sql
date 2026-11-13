-- V12: change management (Phase 2, M21): planned changes to live services, approved before they happen.
--
-- A change is a fourth ticket type, so it shares work_items (queues, search, assignment, history,
-- comments) and adds its own table. Approval reuses the approvals table from V10.

CREATE SEQUENCE change_ref_seq START 1;

ALTER TABLE work_items
    DROP CONSTRAINT work_items_reference_format_check,
    ADD CONSTRAINT work_items_reference_format_check CHECK (reference ~ '^(INC|REQ|PRB|CHG)-[0-9]{6,}$'),
    DROP CONSTRAINT work_items_type_check,
    ADD CONSTRAINT work_items_type_check CHECK (type IN ('INCIDENT', 'SERVICE_REQUEST', 'PROBLEM', 'CHANGE')),
    DROP CONSTRAINT work_items_status_check,
    ADD CONSTRAINT work_items_status_check CHECK (
        (type = 'INCIDENT' AND status IN
            ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'WAITING_FOR_USER', 'RESOLVED', 'CLOSED', 'CANCELLED'))
        OR
        (type = 'SERVICE_REQUEST' AND status IN
            ('SUBMITTED', 'APPROVAL_PENDING', 'APPROVED', 'REJECTED', 'IN_PROGRESS', 'FULFILLED', 'CLOSED', 'CANCELLED'))
        OR
        (type = 'PROBLEM' AND status IN
            ('NEW', 'ASSIGNED', 'INVESTIGATING', 'KNOWN_ERROR', 'RESOLVED', 'CLOSED', 'CANCELLED'))
        OR
        (type = 'CHANGE' AND status IN
            ('NEW', 'ASSIGNED', 'APPROVAL_PENDING', 'APPROVED', 'REJECTED', 'IMPLEMENTING', 'REVIEW', 'CLOSED',
             'CANCELLED'))
    );

CREATE TABLE changes (
    work_item_id        uuid        PRIMARY KEY REFERENCES work_items (id) ON DELETE CASCADE,
    change_type         varchar(20) NOT NULL,
    risk                varchar(20) NOT NULL,
    -- The plan. Complete before the change is submitted; the application enforces that, since it
    -- depends on status.
    implementation_plan text,
    backout_plan        text,
    planned_start       timestamptz,
    planned_end         timestamptz,
    -- The problem this change permanently fixes, if any.
    problem_id          uuid        REFERENCES problems (work_item_id),
    implemented_at      timestamptz,
    -- How it went, recorded during the review.
    outcome             varchar(30),
    review_notes        text,

    CONSTRAINT changes_type_check CHECK (change_type IN ('STANDARD', 'NORMAL', 'EMERGENCY')),
    CONSTRAINT changes_risk_check CHECK (risk IN ('LOW', 'MEDIUM', 'HIGH')),
    -- A standard change is pre-approved because it is routine and low risk.
    CONSTRAINT changes_standard_low_risk_check CHECK (change_type <> 'STANDARD' OR risk = 'LOW'),
    CONSTRAINT changes_window_check CHECK (planned_end IS NULL OR planned_start IS NULL OR planned_end > planned_start),
    CONSTRAINT changes_outcome_check CHECK (
        outcome IS NULL OR outcome IN ('SUCCESSFUL', 'SUCCESSFUL_WITH_ISSUES', 'FAILED', 'BACKED_OUT')),
    CONSTRAINT changes_review_check CHECK ((outcome IS NULL) = (review_notes IS NULL))
);

-- The change schedule and clash detection: changes by planned window.
CREATE INDEX changes_window_idx ON changes (planned_start, planned_end) WHERE planned_start IS NOT NULL;

-- "Which changes fix this problem?" on every problem page.
CREATE INDEX changes_problem_idx ON changes (problem_id) WHERE problem_id IS NOT NULL;
