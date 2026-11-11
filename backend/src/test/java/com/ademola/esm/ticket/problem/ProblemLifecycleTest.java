package com.ademola.esm.ticket.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.common.error.DomainException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.ticket.WorkItemDraft;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.priority.Urgency;
import com.ademola.esm.ticket.workflow.TransitionInput;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The problem enforces its own rules: what must be known before each step, and what reopening forgets. */
class ProblemLifecycleTest {

    static final Instant T0 = Instant.parse("2026-11-11T09:00:00Z");
    static final TransitionInput FIX = new TransitionInput(null, null, " Licence raised to 500 sessions ");

    Problem problem;

    @BeforeEach
    void setUp() {
        problem = new Problem(new WorkItemDraft(
                "PRB-000001",
                "VPN refuses connections",
                "Error 809 at busy times",
                Impact.HIGH,
                Urgency.MEDIUM,
                Priority.P2,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                UUID.randomUUID()));
    }

    @Test
    void startsNewAndBecomesAssignedWhenSomeoneOwnsIt() {
        assertThat(problem.getType()).isEqualTo(WorkItemType.PROBLEM);
        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.NEW);

        problem.assign(problem.getAssignedTeamId(), UUID.randomUUID(), T0);

        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.ASSIGNED);
    }

    @Test
    void losingItsOwnerSendsAnInvestigationBackToTheQueue() {
        investigating();

        problem.assign(problem.getAssignedTeamId(), null, T0);

        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.NEW);
    }

    @Test
    void startingTheInvestigationCountsAsTheFirstResponse() {
        investigating();

        assertThat(problem.getFirstRespondedAt()).isEqualTo(T0);
    }

    @Test
    void aKnownErrorNeedsTheRootCauseAndAWorkaround() {
        investigating();
        assertCode(
                () -> problem.transition("KNOWN_ERROR", TransitionInput.NONE, T0),
                ErrorCode.PROBLEM_ANALYSIS_INCOMPLETE);

        problem.updateAnalysis("Gateway licence is full", null);
        assertCode(
                () -> problem.transition("KNOWN_ERROR", TransitionInput.NONE, T0),
                ErrorCode.PROBLEM_ANALYSIS_INCOMPLETE);

        problem.updateAnalysis("Gateway licence is full", "Use vpn2");
        problem.transition("KNOWN_ERROR", TransitionInput.NONE, T0.plusSeconds(60));

        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.KNOWN_ERROR);
        assertThat(problem.getKnownErrorAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void aKnownErrorKeepsItsAnalysis() {
        knownError();

        assertCode(() -> problem.updateAnalysis("Gateway licence is full", " "), ErrorCode.PROBLEM_ANALYSIS_INCOMPLETE);

        problem.updateAnalysis(" Licence is full by 10:00 ", "Use vpn2");
        assertThat(problem.getRootCause()).isEqualTo("Licence is full by 10:00");
    }

    @Test
    void analysisCanBeClearedWhileStillInvestigating() {
        investigating();
        problem.updateAnalysis("A guess", "Restart");

        problem.updateAnalysis("", null);

        assertThat(problem.getRootCause()).isNull();
        assertThat(problem.getWorkaround()).isNull();
    }

    @Test
    void resolvingNeedsTheRootCauseAndRecordsTheFix() {
        investigating();
        assertCode(() -> problem.transition("RESOLVED", FIX, T0), ErrorCode.PROBLEM_ANALYSIS_INCOMPLETE);
        problem.updateAnalysis("Gateway licence is full", null);
        assertCode(
                () -> problem.transition("RESOLVED", TransitionInput.NONE, T0),
                ErrorCode.TRANSITION_REQUIREMENT_MISSING);

        problem.transition("RESOLVED", FIX, T0.plusSeconds(3600));

        assertThat(problem.getFixNotes()).isEqualTo("Licence raised to 500 sessions");
        assertThat(problem.getResolvedAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(problem.isOpen()).isFalse();
    }

    @Test
    void reopeningForgetsTheFixButKeepsTheAnalysis() {
        knownError();
        problem.transition("RESOLVED", FIX, T0);

        problem.transition(
                "INVESTIGATING", new TransitionInput("Still refused at 10:00", null, null), T0.plusSeconds(60));

        assertThat(problem.getFixNotes()).isNull();
        assertThat(problem.getResolvedAt()).isNull();
        assertThat(problem.getReopenCount()).isEqualTo(1);
        assertThat(problem.getRootCause()).isEqualTo("Gateway licence is full");
        assertThat(problem.getKnownErrorAt()).isEqualTo(T0);
    }

    @Test
    void becomingAKnownErrorAgainKeepsTheFirstDate() {
        knownError();
        problem.transition("RESOLVED", FIX, T0.plusSeconds(60));
        problem.transition("INVESTIGATING", new TransitionInput("Back again", null, null), T0.plusSeconds(120));

        problem.transition("KNOWN_ERROR", TransitionInput.NONE, T0.plusSeconds(180));

        assertThat(problem.getKnownErrorAt()).isEqualTo(T0);
    }

    @Test
    void aKnownErrorReturnedToTheQueueKeepsItsAnalysis() {
        knownError();

        problem.assign(problem.getAssignedTeamId(), null, T0);

        assertThat(problem.getStatus()).isEqualTo(ProblemStatus.NEW);
        assertThat(problem.isOpen()).isTrue();
        assertThat(problem.getWorkaround()).isEqualTo("Use vpn2");
        // No longer a known error, so the analysis may be cleared again.
        problem.updateAnalysis(null, null);
        assertThat(problem.getRootCause()).isNull();
    }

    @Test
    void aClosedProblemCanNoLongerChange() {
        knownError();
        problem.transition("RESOLVED", FIX, T0);
        problem.transition("CLOSED", TransitionInput.NONE, T0.plusSeconds(60));

        assertThat(problem.getClosedAt()).isEqualTo(T0.plusSeconds(60));
        assertCode(() -> problem.updateAnalysis("x", "y"), ErrorCode.PROBLEM_NOT_OPEN);
        assertCode(() -> problem.assign(problem.getAssignedTeamId(), null, T0), ErrorCode.TICKET_NOT_ASSIGNABLE);
    }

    @Test
    void cancellingClosesIt() {
        problem.transition("CANCELLED", new TransitionInput("Duplicate of PRB-000002", null, null), T0);

        assertThat(problem.isTerminal()).isTrue();
        assertThat(problem.getClosedAt()).isEqualTo(T0);
    }

    private void investigating() {
        problem.assign(problem.getAssignedTeamId(), UUID.randomUUID(), T0);
        problem.transition("INVESTIGATING", TransitionInput.NONE, T0);
    }

    private void knownError() {
        investigating();
        problem.updateAnalysis("Gateway licence is full", "Use vpn2");
        problem.transition("KNOWN_ERROR", TransitionInput.NONE, T0);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(code);
    }
}
