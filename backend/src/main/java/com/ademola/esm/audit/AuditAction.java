package com.ademola.esm.audit;

/** Business actions recorded in the audit trail. Stored as text; never rename an existing value. */
public enum AuditAction {
    // users and teams
    USER_CREATED,
    USER_UPDATED,
    PASSWORD_CHANGED,
    TEAM_CREATED,
    TEAM_UPDATED,
    TEAM_MEMBER_ADDED,
    TEAM_MEMBER_REMOVED,
    // categories
    CATEGORY_CREATED,
    CATEGORY_UPDATED,
    // tickets
    TICKET_CREATED
}
