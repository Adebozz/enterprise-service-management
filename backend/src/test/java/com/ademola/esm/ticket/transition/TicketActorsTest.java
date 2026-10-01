package com.ademola.esm.ticket.transition;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.WorkItemDraft;
import com.ademola.esm.ticket.incident.Incident;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.priority.Urgency;
import com.ademola.esm.ticket.workflow.Actor;
import com.ademola.esm.user.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TicketActorsTest {

    final UUID team = UUID.randomUUID();
    final UUID requesterId = UUID.randomUUID();
    final Incident ticket = new Incident(
            new WorkItemDraft(
                    "INC-000001",
                    "t",
                    "d",
                    Impact.LOW,
                    Urgency.LOW,
                    Priority.P4,
                    UUID.randomUUID(),
                    null,
                    requesterId,
                    team),
            null);

    @Test
    void requesterIsOnlyRequester() {
        assertThat(TicketActors.of(user(requesterId, Role.REQUESTER), ticket, Set.of()))
                .containsExactly(Actor.REQUESTER);
    }

    @Test
    void agentInTheTeamIsSupport() {
        assertThat(TicketActors.of(user(UUID.randomUUID(), Role.AGENT), ticket, Set.of(team)))
                .containsExactly(Actor.SUPPORT);
    }

    @Test
    void agentWhoRaisedATicketForAnotherTeamIsOnlyItsRequester() {
        // Can confirm closure of their own ticket, but can't resolve it themselves.
        assertThat(TicketActors.of(user(requesterId, Role.AGENT), ticket, Set.of(UUID.randomUUID())))
                .containsExactly(Actor.REQUESTER);
    }

    @Test
    void agentRequesterInTheOwningTeamIsBoth() {
        assertThat(TicketActors.of(user(requesterId, Role.AGENT), ticket, Set.of(team)))
                .containsExactlyInAnyOrder(Actor.REQUESTER, Actor.SUPPORT);
    }

    @Test
    void adminActsAsSupportEverywhere() {
        assertThat(TicketActors.of(user(UUID.randomUUID(), Role.ADMIN), ticket, Set.of()))
                .containsExactlyInAnyOrder(Actor.ADMIN, Actor.SUPPORT);
    }

    @Test
    void nobodyIsEverSystemThroughTheApi() {
        for (Role role : Role.values()) {
            assertThat(TicketActors.of(user(requesterId, role), ticket, Set.of(team)))
                    .doesNotContain(Actor.SYSTEM);
        }
    }

    private static CurrentUser user(UUID id, Role role) {
        return new CurrentUser(id, role, "x");
    }
}
