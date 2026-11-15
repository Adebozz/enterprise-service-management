-- V14: notifications (Phase 2, M23): telling people about what happened to their tickets.
--
-- Each row is one message to one person, created in the same transaction as the change it reports.
-- It is shown in the app, and the email columns make the table an outbox: a scheduled job sends
-- PENDING rows and records the result, so a mail server being down never rolls back a ticket change.
CREATE TABLE notifications (
    id             uuid         PRIMARY KEY,
    recipient_id   uuid         NOT NULL REFERENCES users (id),
    work_item_id   uuid         NOT NULL REFERENCES work_items (id) ON DELETE CASCADE,
    actor_id       uuid         REFERENCES users (id),
    kind           varchar(30)  NOT NULL,
    message        varchar(500) NOT NULL,
    created_at     timestamptz  NOT NULL,
    read_at        timestamptz,
    email_status   varchar(10)  NOT NULL,
    email_attempts smallint     NOT NULL DEFAULT 0,
    emailed_at     timestamptz,

    CONSTRAINT notifications_kind_check CHECK (kind IN
        ('ASSIGNED', 'STATUS_CHANGED', 'COMMENTED', 'FILE_ADDED', 'SLA_BREACHED', 'APPROVAL_REQUESTED')),
    CONSTRAINT notifications_email_status_check CHECK (email_status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    -- Nobody is told about what they did themselves.
    CONSTRAINT notifications_not_self_check CHECK (actor_id IS NULL OR actor_id <> recipient_id),
    CONSTRAINT notifications_read_check CHECK (read_at IS NULL OR read_at >= created_at)
);

-- The bell: someone's latest notifications, and how many are unread.
CREATE INDEX notifications_recipient_idx ON notifications (recipient_id, created_at DESC, id);
CREATE INDEX notifications_unread_idx ON notifications (recipient_id) WHERE read_at IS NULL;

-- The outbox: what the email job still has to send, oldest first.
CREATE INDEX notifications_pending_email_idx ON notifications (created_at) WHERE email_status = 'PENDING';
