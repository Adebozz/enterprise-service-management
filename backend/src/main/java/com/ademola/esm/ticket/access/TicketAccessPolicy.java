package com.ademola.esm.ticket.access;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemType;
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
 *   <li>Support staff (AGENT, TEAM_LEAD): tickets assigned to them, or to any team they belong to,
 *       and every problem: a known error and its workaround help whoever meets the same incident.
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
        return canView(user, item, teamScopeOf(user));
    }

    /** Teams whose tickets the user handles; empty for requesters. Load once per request and reuse. */
    public Set<UUID> teamScopeOf(CurrentUser user) {
        return user.role().isStaff() ? teams.teamIdsOf(user.id()) : Set.of();
    }

    /** Pure decision function: no I/O, so every rule is unit-testable in isolation. */
    public static boolean canView(CurrentUser user, WorkItem item, Set<UUID> userTeamIds) {
        if (user.role() == Role.ADMIN || user.id().equals(item.getRequesterId())) {
            return true;
        }
        if (!user.role().isStaff()) {
            return false;
        }
        return isSupporting(user, item, userTeamIds) || item.getType() == WorkItemType.PROBLEM;
    }

    /**
     * May see staff-only information about this ticket (internal notes, full history): admins,
     * and staff supporting this particular ticket. Being staff in general is not enough, except on
     * a problem, which has no requester-facing side: all of it is for staff.
     */
    public static boolean canSeeStaffDetails(CurrentUser user, WorkItem item, Set<UUID> userTeamIds) {
        return user.role() == Role.ADMIN
                || isSupporting(user, item, userTeamIds)
                || (user.role().isStaff() && item.getType() == WorkItemType.PROBLEM);
    }

    /** Staff responsible for the ticket: the assignee or a member of the assigned team. */
    public static boolean isSupporting(CurrentUser user, WorkItem item, Set<UUID> userTeamIds) {
        return user.role().isStaff()
                && (user.id().equals(item.getAssigneeId()) || userTeamIds.contains(item.getAssignedTeamId()));
    }
}
