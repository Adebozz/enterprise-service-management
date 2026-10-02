package com.ademola.esm.ticket.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.common.error.DomainException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.ticket.AssignmentChange;
import com.ademola.esm.ticket.WorkItemDraft;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.priority.Urgency;
import com.ademola.esm.ticket.workflow.TransitionInput;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ownership changes drive status through SYSTEM transitions owned by the entity. */
class IncidentAssignmentTest {

    static final Instant T0 = Instant.parse("2026-10-02T09:00:00Z");

    final UUID team = UUID.randomUUID();
    final UUID otherTeam = UUID.randomUUID();
    final UUID agent = UUID.randomUUID();
    final UUID colleague = UUID.randomUUID();
    final Incident incident = new Incident(
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

    @Test
    void gainingAnOwnerMovesNewToAssigned() {
        AssignmentChange change = incident.assign(team, agent, T0);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(change.before().status()).isEqualTo("NEW");
        assertThat(change.after().assigneeId()).isEqualTo(agent);
    }

    @Test
    void losingTheOwnerBeforeWorkStartsGoesBackToNew() {
        incident.assign(team, agent, T0);

        incident.assign(team, null, T0);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.NEW);
    }

    @Test
    void reassigningWorkInProgressKeepsTheStatus() {
        startWork();

        incident.assign(team, colleague, T0);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(incident.getAssigneeId()).isEqualTo(colleague);
    }

    @Test
    void transferringWorkInProgressUnassignedReturnsItToTheNewTeamsQueue() {
        startWork();

        AssignmentChange change = incident.assign(otherTeam, null, T0.plusSeconds(60));

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.NEW);
        assertThat(incident.getAssignedTeamId()).isEqualTo(otherTeam);
        assertThat(change.teamChanged()).isTrue();
        assertThat(incident.getFirstRespondedAt())
                .as("first response stays recorded")
                .isEqualTo(T0);
    }

    @Test
    void waitingForUserCanAlsoReturnToTheQueue() {
        startWork();
        incident.transition("WAITING_FOR_USER", new TransitionInput("which room?", null, null), T0);

        incident.assign(otherTeam, null, T0);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.NEW);
    }

    @Test
    void resolvedOrClosedIncidentsCannotChangeOwner() {
        startWork();
        incident.transition("RESOLVED", new TransitionInput(null, "FIXED", "done"), T0);

        assertThatThrownBy(() -> incident.assign(team, colleague, T0))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.TICKET_NOT_ASSIGNABLE);
    }

    private void startWork() {
        incident.assign(team, agent, T0);
        incident.transition("IN_PROGRESS", TransitionInput.NONE, T0);
    }
}
