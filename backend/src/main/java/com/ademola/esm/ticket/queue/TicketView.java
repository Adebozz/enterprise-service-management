package com.ademola.esm.ticket.queue;

/**
 * Pre-defined slices of the tickets a user may see. A view only ever <em>narrows</em> the
 * caller's visibility; it can't widen it.
 */
public enum TicketView {
    /** Everything the caller may see. */
    ALL,
    /** Tickets the caller raised. */
    REQUESTED,
    /** Tickets assigned to the caller. */
    MINE,
    /** Tickets of the caller's teams. */
    TEAM,
    /** Open, unassigned tickets of the caller's teams (all teams for admins): the pick-up queue. */
    UNASSIGNED
}
