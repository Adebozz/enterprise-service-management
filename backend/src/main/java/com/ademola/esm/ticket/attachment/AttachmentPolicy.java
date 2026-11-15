package com.ademola.esm.ticket.attachment;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.comment.CommentVisibility;
import com.ademola.esm.user.Role;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The same visibility rules as comments: internal files (a packet capture, a screenshot of the
 * admin console) are for the staff supporting this ticket. Callers have already passed
 * {@link TicketAccessPolicy#canView} (otherwise: 404).
 */
final class AttachmentPolicy {

    /** Enough for screenshots and logs, few enough that a ticket stays readable. */
    static final int MAX_PER_TICKET = 20;

    private AttachmentPolicy() {}

    static Set<CommentVisibility> readableBy(CurrentUser user, WorkItem item, Set<UUID> userTeams) {
        return TicketAccessPolicy.canSeeStaffDetails(user, item, userTeams)
                ? EnumSet.allOf(CommentVisibility.class)
                : EnumSet.of(CommentVisibility.PUBLIC);
    }

    static boolean canAdd(CurrentUser user, WorkItem item, Set<UUID> userTeams, CommentVisibility visibility) {
        return visibility == CommentVisibility.PUBLIC || TicketAccessPolicy.canSeeStaffDetails(user, item, userTeams);
    }

    /** Whoever added a file may take it back while the ticket is open; an admin may remove any. */
    static boolean canDelete(CurrentUser user, WorkItem item, Attachment attachment) {
        return !item.isTerminal() && (user.role() == Role.ADMIN || user.id().equals(attachment.getUploadedBy()));
    }
}
