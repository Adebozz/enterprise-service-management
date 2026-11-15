package com.ademola.esm.audit;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An audit entry was just written. Published inside the business transaction, so listeners'
 * changes commit or roll back with it. {@code actorId} is null for system actions.
 */
public record AuditRecordedEvent(
        AuditRecord record, @Nullable UUID actorId, Instant occurredAt) {}
