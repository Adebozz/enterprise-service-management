package com.ademola.esm.ticket.workflow;

/**
 * The caller's <em>relationship to a specific ticket</em>, which is what workflow permissions
 * depend on. A global role isn't enough: an agent is SUPPORT for their team's tickets but only
 * REQUESTER for a ticket they raised in another team's queue.
 */
public enum Actor {
    /** Raised the ticket. */
    REQUESTER,
    /** Support staff responsible for it: a member of the assigned team, or the assignee. */
    SUPPORT,
    ADMIN,
    /** The application itself: assignment rules, scheduled auto-close, approval engine. */
    SYSTEM
}
