-- V13: attachments (Phase 2, M22): files added to a ticket, such as screenshots and logs.
--
-- Metadata and content live in separate tables: listing a ticket's attachments never reads the
-- files, and the content table is the one piece a later object store (S3) replaces. Like
-- comments, an attachment is either part of the conversation (PUBLIC) or for support staff only
-- (INTERNAL).
CREATE TABLE attachments (
    id             uuid         PRIMARY KEY,
    work_item_id   uuid         NOT NULL REFERENCES work_items (id) ON DELETE CASCADE,
    uploaded_by    uuid         NOT NULL REFERENCES users (id),
    visibility     varchar(10)  NOT NULL,
    file_name      varchar(255) NOT NULL,
    content_type   varchar(100) NOT NULL,
    size_bytes     bigint       NOT NULL,
    -- Hex SHA-256 of the content: lets a download be checked and duplicates be spotted.
    sha256         varchar(64)  NOT NULL,
    created_at     timestamptz  NOT NULL,

    CONSTRAINT attachments_visibility_check CHECK (visibility IN ('PUBLIC', 'INTERNAL')),
    CONSTRAINT attachments_file_name_check CHECK (btrim(file_name) <> '' AND file_name !~ '[/\\]'),
    -- 10 MB, matching the upload limit in application.yml.
    CONSTRAINT attachments_size_check CHECK (size_bytes BETWEEN 1 AND 10485760),
    CONSTRAINT attachments_sha256_check CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

-- The ticket's files, oldest first.
CREATE INDEX attachments_work_item_idx ON attachments (work_item_id, created_at, id);

CREATE TABLE attachment_contents (
    attachment_id uuid  PRIMARY KEY REFERENCES attachments (id) ON DELETE CASCADE,
    content       bytea NOT NULL
);
