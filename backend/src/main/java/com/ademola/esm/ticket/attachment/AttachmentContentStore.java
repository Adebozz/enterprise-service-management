package com.ademola.esm.ticket.attachment;

import java.util.Optional;
import java.util.UUID;

/**
 * Where attachment bytes are kept, behind one small interface so the place can change without
 * touching the rules. Today it is PostgreSQL ({@link DatabaseAttachmentContentStore}); the AWS
 * deployment in Phase 3 swaps in S3 (ADR-014).
 */
interface AttachmentContentStore {

    /** Stores the content of a new attachment, inside the caller's transaction where possible. */
    void put(UUID attachmentId, byte[] content);

    Optional<byte[]> get(UUID attachmentId);

    void delete(UUID attachmentId);
}
