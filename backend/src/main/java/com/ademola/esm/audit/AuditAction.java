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
    TICKET_CREATED,
    TICKET_STATUS_CHANGED,
    TICKET_ASSIGNMENT_CHANGED,
    COMMENT_ADDED,
    // service levels
    SLA_BREACHED,
    SLA_POLICY_UPDATED,
    // service catalogue
    CATALOGUE_ITEM_CREATED,
    CATALOGUE_ITEM_UPDATED,
    // approvals
    APPROVAL_REQUESTED,
    APPROVAL_DECIDED,
    APPROVAL_WITHDRAWN,
    // problems
    PROBLEM_ANALYSIS_UPDATED,
    INCIDENT_PROBLEM_LINKED,
    INCIDENT_PROBLEM_UNLINKED,
    // changes
    CHANGE_PLAN_UPDATED,
    CHANGE_OUTCOME_RECORDED
}
