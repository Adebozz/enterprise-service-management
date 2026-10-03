package com.ademola.esm.ticket.history;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.ticket.query.NamedRef;
import jakarta.annotation.Nullable;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** One event in a ticket's history. {@code actor} is null for system actions. */
public record HistoryEntryResponse(
        Instant occurredAt,
        @Nullable NamedRef actor,
        AuditAction action,
        @Nullable JsonNode oldValue,
        @Nullable JsonNode newValue,
        @Nullable JsonNode metadata) {}
