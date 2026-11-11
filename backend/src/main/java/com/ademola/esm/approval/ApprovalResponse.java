package com.ademola.esm.approval;

import com.ademola.esm.ticket.query.NamedRef;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

/** A request's approval. {@code canDecide} tells the caller whether they may approve or reject it now. */
public record ApprovalResponse(
        UUID id,
        ApprovalStatus status,
        Instant requestedAt,
        @Nullable NamedRef decidedBy,
        @Nullable Instant decidedAt,
        @Nullable String comment,
        boolean canDecide,
        long version) {}
