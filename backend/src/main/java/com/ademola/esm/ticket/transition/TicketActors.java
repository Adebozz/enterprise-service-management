package com.ademola.esm.ticket.transition;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.workflow.Actor;
import com.ademola.esm.user.Role;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Works out how a caller relates to a ticket, which is what workflow permissions are expressed in. */
final class TicketActors {

    private TicketActors() {}

    static Set<Actor> of(CurrentUser user, WorkItem item, Set<UUID> userTeamIds) {
        Set<Actor> actors = EnumSet.noneOf(Actor.class);
        if (user.id().equals(item.getRequesterId())) {
            actors.add(Actor.REQUESTER);
        }
        if (TicketAccessPolicy.isSupporting(user, item, userTeamIds)) {
            actors.add(Actor.SUPPORT);
        }
        if (user.role() == Role.ADMIN) {
            // Admins can act as support on any ticket, plus admin-only moves.
            actors.add(Actor.ADMIN);
            actors.add(Actor.SUPPORT);
        }
        return actors;
    }
}
