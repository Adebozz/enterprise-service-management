package com.ademola.esm.ticket.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.common.error.DomainException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.ticket.WorkItemDraft;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.priority.Urgency;
import com.ademola.esm.ticket.workflow.InvalidTransitionException;
import com.ademola.esm.ticket.workflow.TransitionInput;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The entity enforces the state machine and its side effects, regardless of who calls it. */
class IncidentLifecycleTest {

    static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");
    static final TransitionInput RESOLUTION = new TransitionInput(null, "FIXED", "Replaced access point");

    Incident incident;

    @BeforeEach
    void setUp() {
        incident = new Incident(
                new WorkItemDraft(
                        "INC-000001",
                        "Wi-Fi",
                        "Down",
                        Impact.LOW,
                        Urgency.LOW,
                        Priority.P4,
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID()),
                null);
    }

    @Test
    void cannotStartWorkWithoutAnAssignee() {
        ReflectionTestUtils.setField(incident, "status", "ASSIGNED");

        assertCode(() -> incident.transition("IN_PROGRESS", TransitionInput.NONE, T0), ErrorCode.TICKET_NOT_ASSIGNED);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
    }

    @Test
    void firstResponseIsRecordedOnceWhenWorkStarts() {
        inProgress();
        incident.transition(
                "WAITING_FOR_USER", new TransitionInput("Need laptop serial", null, null), T0.plusSeconds(60));
        incident.transition("IN_PROGRESS", TransitionInput.NONE, T0.plusSeconds(600));

        assertThat(incident.getFirstRespondedAt()).isEqualTo(T0);
    }

    @Test
    void resolvingRecordsCodeNotesAndTime() {
        inProgress();

        incident.transition("RESOLVED", RESOLUTION, T0.plus(Duration.ofHours(2)));

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getResolutionCode()).isEqualTo("FIXED");
        assertThat(incident.getResolutionNotes()).isEqualTo("Replaced access point");
        assertThat(incident.getResolvedAt()).isEqualTo(T0.plus(Duration.ofHours(2)));
    }

    @Test
    void resolvingWithoutResolutionOrWithUnknownCodeFails() {
        inProgress();

        assertCode(
                () -> incident.transition("RESOLVED", new TransitionInput(null, "FIXED", " "), T0),
                ErrorCode.TRANSITION_REQUIREMENT_MISSING);
        assertCode(
                () -> incident.transition("RESOLVED", new TransitionInput(null, "MAGIC", "notes"), T0),
                ErrorCode.TRANSITION_REQUIREMENT_MISSING);
    }

    @Test
    void reopeningCountsAndClearsTheFailedResolution() {
        inProgress();
        incident.transition("RESOLVED", RESOLUTION, T0.plusSeconds(60));

        incident.transition("IN_PROGRESS", new TransitionInput("Still dropping", null, null), T0.plusSeconds(120));

        assertThat(incident.getReopenCount()).isEqualTo(1);
        assertThat(incident.getResolvedAt()).isNull();
        assertThat(incident.getResolutionCode()).isNull();
        assertThat(incident.getFirstRespondedAt()).isEqualTo(T0); // unchanged by reopening
    }

    @Test
    void closingStampsClosedAtAndIsFinal() {
        inProgress();
        incident.transition("RESOLVED", RESOLUTION, T0);
        incident.transition("CLOSED", TransitionInput.NONE, T0.plusSeconds(30));

        assertThat(incident.getClosedAt()).isEqualTo(T0.plusSeconds(30));
        assertThatThrownBy(() -> incident.transition("IN_PROGRESS", new TransitionInput("again", null, null), T0))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessage("Incident cannot transition from CLOSED to IN_PROGRESS");
    }

    @Test
    void cancellingRequiresAReason() {
        assertCode(
                () -> incident.transition("CANCELLED", TransitionInput.NONE, T0),
                ErrorCode.TRANSITION_REQUIREMENT_MISSING);

        incident.transition("CANCELLED", new TransitionInput("Raised by mistake", null, null), T0);
        assertThat(incident.getClosedAt()).isEqualTo(T0);
    }

    private void inProgress() {
        ReflectionTestUtils.setField(incident, "status", "ASSIGNED");
        ReflectionTestUtils.setField(incident, "assigneeId", UUID.randomUUID());
        incident.transition("IN_PROGRESS", TransitionInput.NONE, T0);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(code);
    }
}
