package com.ademola.esm.ticket.workflow;

/** Preconditions a transition imposes beyond "this move is allowed". */
public enum Requirement {
    /** A free-text reason (waiting for user, cancelling, reopening). */
    REASON,
    /** Resolution code and notes (resolving an incident). */
    RESOLUTION,
    /** Notes describing what was delivered (fulfilling a request). */
    FULFILMENT_NOTES,
    /** Someone must own the ticket before work can start. */
    ASSIGNEE
}
