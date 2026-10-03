package com.ademola.esm.ticket.comment;

import com.ademola.esm.ticket.query.NamedRef;
import java.time.Instant;
import java.util.UUID;

/** {@code relatedStatus} is set when the comment explains a status change (e.g. WAITING_FOR_USER). */
public record CommentResponse(
        UUID id, NamedRef author, CommentVisibility visibility, String body, String relatedStatus, Instant createdAt) {

    /** Constructor used by the JPQL projection (author name joined in the same query). */
    public CommentResponse(
            UUID id,
            UUID authorId,
            String authorName,
            CommentVisibility visibility,
            String body,
            String relatedStatus,
            Instant createdAt) {
        this(id, new NamedRef(authorId, authorName), visibility, body, relatedStatus, createdAt);
    }
}
