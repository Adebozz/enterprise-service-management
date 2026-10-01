package com.ademola.esm.ticket.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.auth.CurrentUser;
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

class TicketAccessPolicyTest {

    static final UUID NETWORK_TEAM = UUID.randomUUID();
    static final UUID HARDWARE_TEAM = UUID.randomUUID();

    final UUID requesterId = UUID.randomUUID();
    final Incident ticket = incident(requesterId, NETWORK_TEAM);

    @Test
    void requesterSeesOwnTicket() {
        assertThat(canView(user(requesterId, Role.REQUESTER), Set.of())).isTrue();
    }

    @Test
    void requesterNeverSeesSomeoneElsesTicket() {
        assertThat(canView(user(UUID.randomUUID(), Role.REQUESTER), Set.of())).isFalse();
    }

    @Test
    void requesterIsNotGrantedTeamAccessEvenIfTeamIdsWerePassed() {
        // Defence in depth: team membership only counts for staff roles.
        assertThat(canView(user(UUID.randomUUID(), Role.REQUESTER), Set.of(NETWORK_TEAM)))
                .isFalse();
    }

    @Test
    void agentSeesTicketsOfTheirTeams() {
        assertThat(canView(user(UUID.randomUUID(), Role.AGENT), Set.of(NETWORK_TEAM)))
                .isTrue();
    }

    @Test
    void agentDoesNotSeeOtherTeamsTickets() {
        assertThat(canView(user(UUID.randomUUID(), Role.AGENT), Set.of(HARDWARE_TEAM)))
                .isFalse();
    }

    @Test
    void agentSeesTicketAssignedToThemEvenOutsideTheirTeams() {
        CurrentUser agent = user(UUID.randomUUID(), Role.AGENT);
        ReflectionTestUtils.setField(ticket, "assigneeId", agent.id());

        assertThat(canView(agent, Set.of())).isTrue();
    }

    @Test
    void agentSeesTicketTheyRaisedThemselvesEvenInAnotherTeamsQueue() {
        assertThat(canView(user(requesterId, Role.AGENT), Set.of(HARDWARE_TEAM)))
                .isTrue();
    }

    @Test
    void teamLeadIsScopedToTheirTeamsLikeAgents() {
        assertThat(canView(user(UUID.randomUUID(), Role.TEAM_LEAD), Set.of(HARDWARE_TEAM)))
                .isFalse();
        assertThat(canView(user(UUID.randomUUID(), Role.TEAM_LEAD), Set.of(NETWORK_TEAM)))
                .isTrue();
    }

    @Test
    void adminSeesEverything() {
        assertThat(canView(user(UUID.randomUUID(), Role.ADMIN), Set.of())).isTrue();
    }

    private boolean canView(CurrentUser user, Set<UUID> teams) {
        return TicketAccessPolicy.canView(user, ticket, teams);
    }

    private static CurrentUser user(UUID id, Role role) {
        return new CurrentUser(id, role, "Test");
    }

    static Incident incident(UUID requesterId, UUID teamId) {
        return new Incident(
                new WorkItemDraft(
                        "INC-000001",
                        "Wi-Fi down",
                        "No connection",
                        Impact.MEDIUM,
                        Urgency.MEDIUM,
                        Priority.P3,
                        UUID.randomUUID(),
                        null,
                        requesterId,
                        teamId),
                null);
    }
}
