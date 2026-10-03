package com.ademola.esm.ticket.history;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.ticket.query.NamedRef;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** One event in a ticket's history. {@code actor} is null for system actions. */
public record HistoryEntryResponse(
        Instant occurredAt,
        NamedRef actor,
        AuditAction action,
        JsonNode oldValue,
        JsonNode newValue,
        JsonNode metadata) {}
