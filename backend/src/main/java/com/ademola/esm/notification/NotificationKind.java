package com.ademola.esm.notification;

/** What a notification is about. Stored as text; never rename an existing value. */
public enum NotificationKind {
    ASSIGNED,
    STATUS_CHANGED,
    COMMENTED,
    FILE_ADDED,
    SLA_BREACHED,
    APPROVAL_REQUESTED
}
