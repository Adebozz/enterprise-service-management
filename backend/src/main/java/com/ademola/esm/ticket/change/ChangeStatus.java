package com.ademola.esm.ticket.change;

/** Change lifecycle (M21). Allowed transitions are in {@link ChangeWorkflow}. */
public enum ChangeStatus {
    NEW,
    /** Owned by someone, who writes the plan. */
    ASSIGNED,
    APPROVAL_PENDING,
    /** Approved (or, for a standard change, pre-approved) and scheduled for its planned window. */
    APPROVED,
    REJECTED,
    IMPLEMENTING,
    /** Done; how it went is recorded before the change is closed. */
    REVIEW,
    CLOSED,
    CANCELLED
}
