package com.ademola.esm.audit;

import java.util.Map;
import java.util.UUID;

/**
 * What happened, to what, with before/after values. Values are small maps of the fields that
 * changed. Never put secrets (passwords, tokens) in them.
 */
public record AuditRecord(
        AuditAction action,
        AuditEntityType entityType,
        UUID entityId,
        Map<String, ?> oldValue,
        Map<String, ?> newValue,
        Map<String, ?> metadata) {

    public static AuditRecord created(AuditAction action, AuditEntityType type, UUID id, Map<String, ?> newValue) {
        return new AuditRecord(action, type, id, null, newValue, null);
    }

    public static AuditRecord changed(
            AuditAction action, AuditEntityType type, UUID id, Map<String, ?> oldValue, Map<String, ?> newValue) {
        return new AuditRecord(action, type, id, oldValue, newValue, null);
    }

    public static AuditRecord event(AuditAction action, AuditEntityType type, UUID id, Map<String, ?> metadata) {
        return new AuditRecord(action, type, id, null, null, metadata);
    }
}
