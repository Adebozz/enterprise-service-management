package com.ademola.esm.ticket.history;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.ticket.query.NamedRef;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** One event in a ticket's history. {@code actor} is null for system actions. */
/**
 * {@code names} maps user and team ids that appear in the values to display names, so clients can
 * render "assigned to Alex Agent" without further lookups.
 */
public record HistoryEntryResponse(
        Instant occurredAt,
        @Nullable NamedRef actor,
        AuditAction action,
        @Nullable JsonNode oldValue,
        @Nullable JsonNode newValue,
        @Nullable JsonNode metadata,
        Map<String, String> names) {}
