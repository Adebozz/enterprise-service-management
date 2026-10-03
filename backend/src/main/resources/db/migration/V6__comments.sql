-- V6: ticket comments (public conversation + internal notes).
--
-- Comments are immutable: there's no edit or delete in the application; corrections are new comments.
-- related_status is set when the comment was written as part of a status change (e.g. the reason
-- for "waiting for user"), so the UI can show "set status to Waiting for user".
CREATE TABLE comments (
    id             uuid        PRIMARY KEY,
    work_item_id   uuid        NOT NULL REFERENCES work_items (id) ON DELETE CASCADE,
    author_id      uuid        NOT NULL REFERENCES users (id),
    visibility     varchar(10) NOT NULL,
    body           text        NOT NULL,
    related_status varchar(30),
    created_at     timestamptz NOT NULL,

    CONSTRAINT comments_visibility_check CHECK (visibility IN ('PUBLIC', 'INTERNAL')),
    CONSTRAINT comments_body_check CHECK (btrim(body) <> '' AND char_length(body) <= 10000)
);

-- The ticket's thread, oldest first (id breaks ties between comments in the same instant).
CREATE INDEX comments_work_item_idx ON comments (work_item_id, created_at, id);
