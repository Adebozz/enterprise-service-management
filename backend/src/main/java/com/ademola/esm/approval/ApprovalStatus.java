package com.ademola.esm.approval;

public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /** The request left "awaiting approval" without a decision, e.g. the requester cancelled it. */
    WITHDRAWN
}
