package com.ademola.esm.ticket.assignment;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.user.Role;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who may change a ticket's ownership. Pure function of (caller, caller's teams, ticket, target),
 * so each rule is unit-tested in isolation.
 *
 * <table>
 *   <caption>Rules (admins may do anything)</caption>
 *   <tr><td>take: assign an <em>unassigned</em> ticket to myself within its team</td><td>any staff member of that team</td></tr>
 *   <tr><td>release: unassign myself</td><td>the current assignee</td></tr>
 *   <tr><td>assign/reassign someone else within the team</td><td>TEAM_LEAD of that team</td></tr>
 *   <tr><td>transfer to another team, unassigned</td><td>support on the ticket</td></tr>
 *   <tr><td>transfer and choose the new assignee</td><td>TEAM_LEAD of the target team, who is also support on the ticket</td></tr>
 * </table>
 */
final class AssignmentPolicy {

    private AssignmentPolicy() {}

    static void check(CurrentUser user, Set<UUID> userTeams, WorkItem item, UUID targetTeam, UUID targetAssignee) {
        if (user.role() == Role.ADMIN) {
            return;
        }
        boolean sameTeam = targetTeam.equals(item.getAssignedTeamId());
        if (sameTeam) {
            // Taking only applies to unassigned tickets: taking a colleague's ticket is a lead decision.
            boolean takingIt =
                    user.id().equals(targetAssignee) && item.getAssigneeId() == null && userTeams.contains(targetTeam);
            boolean releasingOwn = targetAssignee == null && user.id().equals(item.getAssigneeId());
            boolean leadOfTeam = isLeadOf(user, userTeams, targetTeam);
            if (takingIt || releasingOwn || leadOfTeam) {
                return;
            }
            throw denied(
                    Objects.equals(targetAssignee, user.id())
                            ? "Only members of the ticket's team can take it"
                            : "Only a team lead can assign tickets to other people");
        }

        if (!TicketAccessPolicy.isSupporting(user, item, userTeams)) {
            throw denied("Only support staff handling this ticket can transfer it");
        }
        if (targetAssignee != null && !isLeadOf(user, userTeams, targetTeam)) {
            throw denied("Transfer the ticket unassigned; the receiving team assigns it");
        }
    }

    private static boolean isLeadOf(CurrentUser user, Set<UUID> userTeams, UUID team) {
        return user.role().includes(Role.TEAM_LEAD) && userTeams.contains(team);
    }

    private static BusinessRuleException denied(String message) {
        return new BusinessRuleException(ErrorCode.ASSIGNMENT_NOT_PERMITTED, message);
    }
}
