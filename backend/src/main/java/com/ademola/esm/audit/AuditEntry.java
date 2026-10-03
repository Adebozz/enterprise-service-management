package com.ademola.esm.audit;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A read-only view of an audit event with its JSON values parsed (not JSON-in-a-string). */
public record AuditEntry(
        UUID id,
        Instant occurredAt,
        UUID actorId,
        AuditAction action,
        JsonNode oldValue,
        JsonNode newValue,
        JsonNode metadata) {}
