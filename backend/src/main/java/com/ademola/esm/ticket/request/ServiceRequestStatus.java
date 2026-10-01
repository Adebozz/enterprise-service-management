package com.ademola.esm.ticket.request;

/** Service-request lifecycle. Approval states are used once the approval engine exists (Phase 2). */
public enum ServiceRequestStatus {
    SUBMITTED,
    APPROVAL_PENDING,
    APPROVED,
    REJECTED,
    IN_PROGRESS,
    FULFILLED,
    CLOSED,
    CANCELLED
}
