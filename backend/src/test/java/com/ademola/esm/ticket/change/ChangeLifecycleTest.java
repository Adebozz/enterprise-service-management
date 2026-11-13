package com.ademola.esm.ticket.change;

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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** The change enforces its own rules: what the plan needs, when it may happen, and what closing needs. */
class ChangeLifecycleTest {

    static final Instant T0 = Instant.parse("2026-11-13T09:00:00Z");
    static final Instant TOMORROW = T0.plus(Duration.ofDays(1));

    @Test
    void startsNewAndBecomesAssignedWhenSomeoneOwnsIt() {
        Change change = change(ChangeType.NORMAL, ChangeRisk.MEDIUM, plan(TOMORROW));
        assertThat(change.getType()).isEqualTo(WorkItemType.CHANGE);
        assertThat(change.getStatus()).isEqualTo(ChangeStatus.NEW);

        change.assign(change.getAssignedTeamId(), UUID.randomUUID(), T0);
        assertThat(change.getStatus()).isEqualTo(ChangeStatus.ASSIGNED);

        change.assign(change.getAssignedTeamId(), null, T0);
        assertThat(change.getStatus()).isEqualTo(ChangeStatus.NEW);
    }

    @Test
    void aSubmittedChangeKeepsItsPlaceWhenItLosesItsOwner() {
        Change change = assigned(ChangeType.NORMAL, ChangeRisk.MEDIUM, plan(TOMORROW));
        change.transition("APPROVAL_PENDING", TransitionInput.NONE, T0);

        change.assign(change.getAssignedTeamId(), null, T0);

        assertThat(change.getStatus()).isEqualTo(ChangeStatus.APPROVAL_PENDING);
    }

    @Test
    void aStandardChangeMustBeLowRisk() {
        assertCode(() -> change(ChangeType.STANDARD, ChangeRisk.MEDIUM, plan(TOMORROW)), ErrorCode.CHANGE_PLAN_INVALID);

        Change change = change(ChangeType.NORMAL, ChangeRisk.HIGH, plan(TOMORROW));
        assertCode(
                () -> change.updatePlan(new ChangePlan(
                        ChangeType.STANDARD, ChangeRisk.HIGH, "do", "undo", TOMORROW, TOMORROW.plusSeconds(60))),
                ErrorCode.CHANGE_PLAN_INVALID);
    }

    @Test
    void theWindowMustEndAfterItStarts() {
        assertCode(
                () -> change(
                        ChangeType.NORMAL,
                        ChangeRisk.LOW,
                        new ChangePlan(ChangeType.NORMAL, ChangeRisk.LOW, null, null, TOMORROW, TOMORROW)),
                ErrorCode.CHANGE_PLAN_INVALID);
    }

    @Test
    void submittingNeedsTheWholePlan() {
        Change change = assigned(
                ChangeType.NORMAL,
                ChangeRisk.MEDIUM,
                new ChangePlan(ChangeType.NORMAL, ChangeRisk.MEDIUM, " ", null, null, null));

        assertThat(change.missingFromPlan())
                .containsExactly("an implementation plan", "a backout plan", "a planned window");
        assertThatThrownBy(() -> change.transition("APPROVAL_PENDING", TransitionInput.NONE, T0))
                .hasMessageContaining("an implementation plan, a backout plan, a planned window");
        assertThat(change.getStatus()).isEqualTo(ChangeStatus.ASSIGNED);
    }

    @Test
    void onlyAnEmergencyMayStartStraightAway() {
        Change normal = assigned(ChangeType.NORMAL, ChangeRisk.MEDIUM, plan(T0));
        assertCode(
                () -> normal.transition("APPROVAL_PENDING", TransitionInput.NONE, T0), ErrorCode.CHANGE_PLAN_INVALID);

        Change emergency = assigned(ChangeType.EMERGENCY, ChangeRisk.HIGH, plan(T0.minusSeconds(60)));
        emergency.transition("APPROVAL_PENDING", TransitionInput.NONE, T0);
        assertThat(emergency.getStatus()).isEqualTo(ChangeStatus.APPROVAL_PENDING);
    }

    @Test
    void aStandardChangeIsScheduledWithoutApprovalAndOthersAreNot() {
        Change standard = assigned(ChangeType.STANDARD, ChangeRisk.LOW, plan(TOMORROW));
        assertThat(standard.offersMoveTo("APPROVAL_PENDING")).isFalse();
        assertThat(standard.offersMoveTo("APPROVED")).isTrue();
        assertCode(
                () -> standard.transition("APPROVAL_PENDING", TransitionInput.NONE, T0), ErrorCode.CHANGE_PLAN_INVALID);
        standard.transition("APPROVED", TransitionInput.NONE, T0);
        assertThat(standard.getStatus()).isEqualTo(ChangeStatus.APPROVED);
        assertThat(standard.getFirstRespondedAt()).isEqualTo(T0);

        Change normal = assigned(ChangeType.NORMAL, ChangeRisk.LOW, plan(TOMORROW));
        assertThat(normal.offersMoveTo("APPROVAL_PENDING")).isTrue();
        assertThat(normal.offersMoveTo("APPROVED")).isFalse();
        assertThat(normal.offersMoveTo("CANCELLED")).isTrue();
        assertCode(() -> normal.transition("APPROVED", TransitionInput.NONE, T0), ErrorCode.CHANGE_PLAN_INVALID);
    }

    @Test
    void thePlanIsFixedOnceSubmitted() {
        Change change = assigned(ChangeType.NORMAL, ChangeRisk.MEDIUM, plan(TOMORROW));
        change.transition("APPROVAL_PENDING", TransitionInput.NONE, T0);
        // An approval decision is a SYSTEM move, and is offered whatever the change type.
        assertThat(change.offersMoveTo("APPROVED")).isTrue();

        assertCode(() -> change.updatePlan(plan(TOMORROW)), ErrorCode.CHANGE_NOT_EDITABLE);
    }

    @Test
    void carryingOutAndReviewingAChange() {
        Change change = assigned(ChangeType.STANDARD, ChangeRisk.LOW, plan(TOMORROW));
        change.transition("APPROVED", TransitionInput.NONE, T0);
        assertCode(() -> change.recordOutcome(ChangeOutcome.SUCCESSFUL, "Fine"), ErrorCode.CHANGE_NOT_EDITABLE);

        change.transition("IMPLEMENTING", TransitionInput.NONE, TOMORROW);
        assertThat(change.getImplementedAt()).isEqualTo(TOMORROW);
        change.transition("REVIEW", TransitionInput.NONE, TOMORROW);
        assertThat(change.getResolvedAt()).isEqualTo(TOMORROW);
        assertCode(
                () -> change.transition("CLOSED", TransitionInput.NONE, TOMORROW), ErrorCode.CHANGE_REVIEW_INCOMPLETE);

        change.recordOutcome(ChangeOutcome.BACKED_OUT, "  Rolled back: the printers rejected the image. ");
        change.transition("CLOSED", TransitionInput.NONE, TOMORROW);

        assertThat(change.getOutcome()).isEqualTo(ChangeOutcome.BACKED_OUT);
        assertThat(change.getReviewNotes()).isEqualTo("Rolled back: the printers rejected the image.");
        assertThat(change.getClosedAt()).isEqualTo(TOMORROW);
        assertThat(change.isTerminal()).isTrue();
        assertCode(() -> change.assign(change.getAssignedTeamId(), null, TOMORROW), ErrorCode.TICKET_NOT_ASSIGNABLE);
    }

    @Test
    void rejectionAndCancellationEndTheChange() {
        Change rejected = assigned(ChangeType.NORMAL, ChangeRisk.MEDIUM, plan(TOMORROW));
        rejected.transition("APPROVAL_PENDING", TransitionInput.NONE, T0);
        rejected.transition("REJECTED", TransitionInput.NONE, T0);
        assertThat(rejected.getClosedAt()).isEqualTo(T0);

        Change cancelled = assigned(ChangeType.NORMAL, ChangeRisk.MEDIUM, plan(TOMORROW));
        cancelled.transition("CANCELLED", new TransitionInput("No longer needed", null, null), T0);
        assertThat(cancelled.getClosedAt()).isEqualTo(T0);
        assertThat(cancelled.isPlanEditable()).isFalse();
    }

    private static ChangePlan plan(Instant start) {
        return new ChangePlan(
                ChangeType.NORMAL, ChangeRisk.MEDIUM, " Do it ", " Undo it ", start, start.plus(Duration.ofHours(1)));
    }

    private static Change assigned(ChangeType type, ChangeRisk risk, ChangePlan plan) {
        Change change = change(type, risk, plan);
        change.assign(change.getAssignedTeamId(), UUID.randomUUID(), T0);
        return change;
    }

    private static Change change(ChangeType type, ChangeRisk risk, ChangePlan plan) {
        return new Change(
                new WorkItemDraft(
                        "CHG-000001",
                        "Upgrade the VPN licence",
                        "More sessions",
                        Impact.MEDIUM,
                        Urgency.MEDIUM,
                        Priority.P3,
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID()),
                new ChangePlan(
                        type,
                        risk,
                        plan.implementationPlan(),
                        plan.backoutPlan(),
                        plan.plannedStart(),
                        plan.plannedEnd()),
                null);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
