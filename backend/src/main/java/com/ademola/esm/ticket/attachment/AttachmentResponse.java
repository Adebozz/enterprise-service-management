package com.ademola.esm.ticket.attachment;

import com.ademola.esm.ticket.comment.CommentVisibility;
import com.ademola.esm.ticket.query.NamedRef;
import java.time.Instant;
import java.util.UUID;

/** {@code canDelete}: whether the caller may remove it (who added it, or an admin, while the ticket is open). */
public record AttachmentResponse(
        UUID id,
        String fileName,
        String contentType,
        long sizeBytes,
        CommentVisibility visibility,
        NamedRef uploadedBy,
        Instant createdAt,
        boolean canDelete) {}
