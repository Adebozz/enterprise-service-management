-- V11: problem management (Phase 2, M20): the underlying cause behind one or more incidents.
--
-- A problem is a third ticket type, so it shares work_items (queues, search, assignment, history and
-- comments work unchanged) and adds its own table. Incidents point at the problem that explains them.

CREATE SEQUENCE problem_ref_seq START 1;

ALTER TABLE work_items
    DROP CONSTRAINT work_items_reference_format_check,
    ADD CONSTRAINT work_items_reference_format_check CHECK (reference ~ '^(INC|REQ|PRB)-[0-9]{6,}$'),
    DROP CONSTRAINT work_items_type_check,
    ADD CONSTRAINT work_items_type_check CHECK (type IN ('INCIDENT', 'SERVICE_REQUEST', 'PROBLEM')),
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
    );

CREATE TABLE problems (
    work_item_id   uuid    PRIMARY KEY REFERENCES work_items (id) ON DELETE CASCADE,
    -- Why the incidents happen, and how people can carry on meanwhile. Both are known before the
    -- problem becomes a known error; the application enforces that, since it depends on status.
    root_cause     text,
    workaround     text,
    known_error_at timestamptz,
    -- What permanently removed the cause.
    fix_notes      text,
    reopen_count   integer NOT NULL DEFAULT 0,

    CONSTRAINT problems_reopen_count_check CHECK (reopen_count >= 0)
);

-- An incident is explained by at most one problem; a problem explains any number of incidents.
-- Problems are cancelled or closed, never deleted, so the key never blocks anything.
ALTER TABLE incidents
    ADD COLUMN problem_id uuid REFERENCES problems (work_item_id);

-- "Which incidents does this problem explain?" on every problem page.
CREATE INDEX incidents_problem_idx ON incidents (problem_id) WHERE problem_id IS NOT NULL;
