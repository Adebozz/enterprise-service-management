package com.ademola.esm.ticket.problem;

/** Problem lifecycle (M20). Allowed transitions are in {@link ProblemWorkflow}. */
public enum ProblemStatus {
    NEW,
    ASSIGNED,
    INVESTIGATING,
    /** Root cause and workaround are known; the permanent fix is still to come. */
    KNOWN_ERROR,
    RESOLVED,
    CLOSED,
    CANCELLED
}
