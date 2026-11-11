package com.ademola.esm.approval;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.user.Role;
import java.util.Set;
import java.util.UUID;

/**
 * Who may approve or reject a request. Pure, so each rule is unit-tested directly.
 *
 * <ul>
 *   <li>Team leads of the team the request is assigned to, decided now: transferring a pending
 *       request moves its approval to the new team's leads.
 *   <li>Admins, for any request.
 *   <li>Never the requester, whatever their role: nobody signs off their own request.
 * </ul>
 */
final class ApprovalPolicy {

    private ApprovalPolicy() {}

    static boolean canDecide(CurrentUser user, UUID requesterId, UUID assignedTeamId, Set<UUID> userTeamIds) {
        if (user.id().equals(requesterId)) {
            return false;
        }
        return user.role() == Role.ADMIN || (user.role() == Role.TEAM_LEAD && userTeamIds.contains(assignedTeamId));
    }
}
