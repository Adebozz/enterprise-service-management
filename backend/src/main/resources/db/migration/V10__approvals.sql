-- V10: approvals (Phase 2, M19): catalogue items can need sign-off before work starts.

ALTER TABLE catalogue_items ADD COLUMN approval_required boolean NOT NULL DEFAULT false;

-- One approval per request (single-stage). Who may decide is not stored: it follows the request's
-- assigned team (its team leads) and admins, decided at the time of the decision.
CREATE TABLE approvals (
    id           uuid        PRIMARY KEY,
    work_item_id uuid        NOT NULL REFERENCES work_items (id) ON DELETE CASCADE,
    status       varchar(20) NOT NULL,
    decided_by   uuid        REFERENCES users (id),
    decided_at   timestamptz,
    comment      text,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint      NOT NULL DEFAULT 0,

    CONSTRAINT approvals_work_item_uk UNIQUE (work_item_id),
    CONSTRAINT approvals_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    -- Only a pending approval is undecided; only approvals and rejections have a person behind them
    -- (a withdrawal happens because the request was cancelled); a rejection always says why.
    CONSTRAINT approvals_decided_check CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CONSTRAINT approvals_decider_check CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_by IS NOT NULL)),
    CONSTRAINT approvals_rejection_reason_check CHECK (status <> 'REJECTED' OR length(trim(comment)) > 0),
    CONSTRAINT approvals_comment_length_check CHECK (comment IS NULL OR length(comment) <= 2000)
);

-- The approvals inbox: pending ones, oldest first.
CREATE INDEX approvals_pending_idx ON approvals (created_at) WHERE status = 'PENDING';
