package com.ademola.esm.ticket.access;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.user.Role;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may see a ticket. Enforced on the server for every read; the frontend hiding a button is
 * never relied on.
 *
 * <ul>
 *   <li>ADMIN: every ticket.
 *   <li>Anyone: tickets they raised.
 *   <li>Support staff (AGENT, TEAM_LEAD): tickets assigned to them, or to any team they belong to.
 * </ul>
 *
 * A requester never sees another requester's ticket, and an agent never sees another team's queue
 * (unless they raised that ticket themselves).
 */
@Component
public class TicketAccessPolicy {

    private final TeamService teams;

    public TicketAccessPolicy(TeamService teams) {
        this.teams = teams;
    }

    public boolean canView(CurrentUser user, WorkItem item) {
        Set<UUID> teamIds = user.role().isStaff() ? teams.teamIdsOf(user.id()) : Set.of();
        return canView(user, item, teamIds);
    }

    /** Pure decision function: no I/O, so every rule is unit-testable in isolation. */
    static boolean canView(CurrentUser user, WorkItem item, Set<UUID> userTeamIds) {
        if (user.role() == Role.ADMIN || user.id().equals(item.getRequesterId())) {
            return true;
        }
        if (!user.role().isStaff()) {
            return false;
        }
        return user.id().equals(item.getAssigneeId()) || userTeamIds.contains(item.getAssignedTeamId());
    }
}
