package com.ademola.esm.ticket.request;

/** Service-request lifecycle. Approval states are driven by the {@code approval} module (M19). */
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
