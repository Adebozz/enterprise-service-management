package com.ademola.esm.ticket.assignment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.ticket.WorkItemDraft;
import com.ademola.esm.ticket.incident.Incident;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.priority.Urgency;
import com.ademola.esm.user.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AssignmentPolicyTest {

    final UUID network = UUID.randomUUID();
    final UUID hardware = UUID.randomUUID();
    final CurrentUser agent = user(Role.AGENT);
    final CurrentUser colleague = user(Role.AGENT);
    final CurrentUser lead = user(Role.TEAM_LEAD);
    final Incident ticket = incidentIn(network);

    // ----- within the team ----------------------------------------------------------------------

    @Test
    void agentCanTakeAnUnassignedTicketFromTheirTeamQueue() {
        allowed(agent, Set.of(network), network, agent.id());
    }

    @Test
    void agentCannotTakeATicketFromAnotherTeam() {
        denied(agent, Set.of(hardware), network, agent.id());
    }

    @Test
    void agentCannotTakeATicketAlreadyOwnedByAColleague() {
        assignTo(colleague);

        denied(agent, Set.of(network), network, agent.id());
    }

    @Test
    void agentCanReleaseTheirOwnTicketButNotSomeoneElses() {
        assignTo(agent);
        allowed(agent, Set.of(network), network, null);

        assignTo(colleague);
        denied(agent, Set.of(network), network, null);
    }

    @Test
    void agentCannotAssignToSomeoneElse() {
        denied(agent, Set.of(network), network, colleague.id());
    }

    @Test
    void teamLeadAssignsAndReassignsWithinTheirTeam() {
        allowed(lead, Set.of(network), network, colleague.id());
        assignTo(agent);
        allowed(lead, Set.of(network), network, colleague.id());
        allowed(lead, Set.of(network), network, null);
    }

    @Test
    void teamLeadOfAnotherTeamCannotAssign() {
        denied(lead, Set.of(hardware), network, colleague.id());
    }

    // ----- transfers ----------------------------------------------------------------------------

    @Test
    void supportCanTransferUnassignedToAnotherTeam() {
        allowed(agent, Set.of(network), hardware, null);
    }

    @Test
    void transferringAndPickingTheNewOwnerNeedsTheTargetTeamsLead() {
        denied(agent, Set.of(network), hardware, colleague.id());
        allowed(lead, Set.of(network, hardware), hardware, colleague.id());
        denied(lead, Set.of(network), hardware, colleague.id()); // not lead of the target team
    }

    @Test
    void nonSupportStaffCannotTransfer() {
        denied(agent, Set.of(hardware), hardware, null); // can't push another team's ticket into their own queue
    }

    @Test
    void currentAssigneeOutsideTheTeamCanStillTransferIt() {
        assignTo(agent);

        allowed(agent, Set.of(), hardware, null);
    }

    @Test
    void adminCanDoAnything() {
        allowed(user(Role.ADMIN), Set.of(), hardware, colleague.id());
    }

    // ----- helpers ------------------------------------------------------------------------------

    private void allowed(CurrentUser who, Set<UUID> teams, UUID targetTeam, UUID targetAssignee) {
        assertThatCode(() -> AssignmentPolicy.check(who, teams, ticket, targetTeam, targetAssignee))
                .doesNotThrowAnyException();
    }

    private void denied(CurrentUser who, Set<UUID> teams, UUID targetTeam, UUID targetAssignee) {
        assertThatThrownBy(() -> AssignmentPolicy.check(who, teams, ticket, targetTeam, targetAssignee))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code())
                .isEqualTo(ErrorCode.ASSIGNMENT_NOT_PERMITTED);
    }

    private void assignTo(CurrentUser who) {
        ReflectionTestUtils.setField(ticket, "assigneeId", who.id());
    }

    private static CurrentUser user(Role role) {
        return new CurrentUser(UUID.randomUUID(), role, role.name());
    }

    private static Incident incidentIn(UUID team) {
        return new Incident(
                new WorkItemDraft(
                        "INC-000001",
                        "t",
                        "d",
                        Impact.LOW,
                        Urgency.LOW,
                        Priority.P4,
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        team),
                null);
    }
}
