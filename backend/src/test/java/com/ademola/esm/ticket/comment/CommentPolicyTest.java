package com.ademola.esm.ticket.comment;

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

class CommentPolicyTest {

    final UUID network = UUID.randomUUID();
    final UUID hardware = UUID.randomUUID();
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
                    network),
            null);

    @Test
    void requesterReadsAndWritesOnlyPublic() {
        CurrentUser requester = new CurrentUser(requesterId, Role.REQUESTER, "R");

        assertThat(CommentPolicy.readableBy(requester, ticket, Set.of())).containsExactly(CommentVisibility.PUBLIC);
        assertThat(CommentPolicy.canWrite(requester, ticket, Set.of(), CommentVisibility.INTERNAL))
                .isFalse();
        assertThat(CommentPolicy.canWrite(requester, ticket, Set.of(), CommentVisibility.PUBLIC))
                .isTrue();
    }

    @Test
    void agentWhoRaisedATicketInAnotherTeamsQueueIsTreatedAsARequester() {
        // Staff in general, but only the requester of THIS ticket: no access to Network's notes.
        CurrentUser hardwareAgentAsRequester = new CurrentUser(requesterId, Role.AGENT, "HW");

        assertThat(CommentPolicy.readableBy(hardwareAgentAsRequester, ticket, Set.of(hardware)))
                .containsExactly(CommentVisibility.PUBLIC);
        assertThat(CommentPolicy.canWrite(
                        hardwareAgentAsRequester, ticket, Set.of(hardware), CommentVisibility.INTERNAL))
                .isFalse();
    }

    @Test
    void supportingAgentSeesAndWritesInternalNotes() {
        CurrentUser agent = new CurrentUser(UUID.randomUUID(), Role.AGENT, "A");

        assertThat(CommentPolicy.readableBy(agent, ticket, Set.of(network)))
                .containsExactlyInAnyOrder(CommentVisibility.PUBLIC, CommentVisibility.INTERNAL);
        assertThat(CommentPolicy.canWrite(agent, ticket, Set.of(network), CommentVisibility.INTERNAL))
                .isTrue();
    }

    @Test
    void adminSeesInternalNotesEverywhere() {
        assertThat(CommentPolicy.readableBy(new CurrentUser(UUID.randomUUID(), Role.ADMIN, "Ad"), ticket, Set.of()))
                .contains(CommentVisibility.INTERNAL);
    }

    @Test
    void onlySupportsPublicReplyCountsAsFirstResponse() {
        CurrentUser agent = new CurrentUser(UUID.randomUUID(), Role.AGENT, "A");
        CurrentUser requester = new CurrentUser(requesterId, Role.REQUESTER, "R");

        assertThat(CommentPolicy.countsAsFirstResponse(agent, ticket, Set.of(network), CommentVisibility.PUBLIC))
                .isTrue();
        assertThat(CommentPolicy.countsAsFirstResponse(agent, ticket, Set.of(network), CommentVisibility.INTERNAL))
                .as("an internal note is not a response to the user")
                .isFalse();
        assertThat(CommentPolicy.countsAsFirstResponse(requester, ticket, Set.of(), CommentVisibility.PUBLIC))
                .isFalse();
    }
}
