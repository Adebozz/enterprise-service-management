package com.ademola.esm.ticket.comment;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who may read and write internal notes. The question is never "is the caller staff?" but "is the
 * caller supporting <em>this</em> ticket?". An agent who raised a ticket that sits in another
 * team's queue is only its requester there, and must not see that team's internal notes.
 *
 * <p>Callers have already passed {@link TicketAccessPolicy#canView} (otherwise: 404).
 */
final class CommentPolicy {

    private CommentPolicy() {}

    static boolean canSeeInternal(CurrentUser user, WorkItem item, Set<UUID> userTeams) {
        return TicketAccessPolicy.canSeeStaffDetails(user, item, userTeams);
    }

    static Set<CommentVisibility> readableBy(CurrentUser user, WorkItem item, Set<UUID> userTeams) {
        return canSeeInternal(user, item, userTeams)
                ? EnumSet.allOf(CommentVisibility.class)
                : EnumSet.of(CommentVisibility.PUBLIC);
    }

    static boolean canWrite(CurrentUser user, WorkItem item, Set<UUID> userTeams, CommentVisibility visibility) {
        return visibility == CommentVisibility.PUBLIC || canSeeInternal(user, item, userTeams);
    }

    /** A support member's public reply is the ticket's "first response" for SLA purposes. */
    static boolean countsAsFirstResponse(
            CurrentUser user, WorkItem item, Set<UUID> userTeams, CommentVisibility visibility) {
        return visibility == CommentVisibility.PUBLIC && canSeeInternal(user, item, userTeams);
    }
}
