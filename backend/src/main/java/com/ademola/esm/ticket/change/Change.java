package com.ademola.esm.ticket.change;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemDraft;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.workflow.TransitionInput;
import com.ademola.esm.ticket.workflow.WorkflowDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A planned change to a live service ("Upgrade the VPN gateway licence"). It is planned, approved
 * (unless it is a standard change), carried out in its window, and reviewed.
 */
@Entity
@Table(name = "changes")
@DiscriminatorValue("CHANGE")
@PrimaryKeyJoinColumn(name = "work_item_id")
public class Change extends WorkItem {

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChangeRisk risk;

    @Column(name = "implementation_plan", columnDefinition = "text")
    private String implementationPlan;

    @Column(name = "backout_plan", columnDefinition = "text")
    private String backoutPlan;

    @Column(name = "planned_start")
    private Instant plannedStart;

    @Column(name = "planned_end")
    private Instant plannedEnd;

    @Column(name = "problem_id")
    private UUID problemId;

    @Column(name = "implemented_at")
    private Instant implementedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ChangeOutcome outcome;

    @Column(name = "review_notes", columnDefinition = "text")
    private String reviewNotes;

    protected Change() {}

    public Change(WorkItemDraft draft, ChangePlan plan, UUID problemId) {
        super(draft, ChangeStatus.NEW.name());
        applyPlan(plan);
        this.problemId = problemId;
    }

    @Override
    public WorkItemType getType() {
        return WorkItemType.CHANGE;
    }

    @Override
    public WorkflowDefinition<ChangeStatus> workflow() {
        return ChangeWorkflow.DEFINITION;
    }

    /** The plan can change until the change is submitted; after that it is what was approved. */
    public boolean isPlanEditable() {
        return getStatus() == ChangeStatus.NEW || getStatus() == ChangeStatus.ASSIGNED;
    }

    /** Replaces the plan. Blank text clears a field; the rules that hold at all times are checked. */
    public void updatePlan(ChangePlan plan) {
        if (!isPlanEditable()) {
            throw new BusinessRuleException(
                    ErrorCode.CHANGE_NOT_EDITABLE,
                    "The plan of a change in status %s can't be changed".formatted(getStatus()));
        }
        applyPlan(plan);
    }

    /** The review. Recorded while the change is being carried out or afterwards, before closing. */
    public void recordOutcome(ChangeOutcome newOutcome, String notes) {
        if (getStatus() != ChangeStatus.IMPLEMENTING && getStatus() != ChangeStatus.REVIEW) {
            throw new BusinessRuleException(
                    ErrorCode.CHANGE_NOT_EDITABLE,
                    "The outcome is recorded once implementation has started, not in status %s".formatted(getStatus()));
        }
        this.outcome = newOutcome;
        this.reviewNotes = notes.trim();
    }

    /** What the plan still lacks before the change can be submitted; empty when it is complete. */
    public List<String> missingFromPlan() {
        List<String> missing = new ArrayList<>();
        if (implementationPlan == null) {
            missing.add("an implementation plan");
        }
        if (backoutPlan == null) {
            missing.add("a backout plan");
        }
        if (plannedStart == null || plannedEnd == null) {
            missing.add("a planned window");
        }
        return missing;
    }

    @Override
    public boolean offersMoveTo(String targetStatus) {
        if (getStatus() != ChangeStatus.ASSIGNED) {
            return true;
        }
        return switch (ChangeStatus.valueOf(targetStatus)) {
            case APPROVAL_PENDING -> changeType != ChangeType.STANDARD;
            case APPROVED -> changeType == ChangeType.STANDARD;
            default -> true;
        };
    }

    @Override
    protected void checkCanEnter(String targetStatus, Instant now) {
        ChangeStatus target = ChangeStatus.valueOf(targetStatus);
        boolean submitting = getStatus() == ChangeStatus.ASSIGNED
                && (target == ChangeStatus.APPROVAL_PENDING || target == ChangeStatus.APPROVED);
        if (submitting) {
            checkCanSubmit(target, now);
        } else if (target == ChangeStatus.CLOSED && outcome == null) {
            throw new BusinessRuleException(
                    ErrorCode.CHANGE_REVIEW_INCOMPLETE, "Record how the change went before closing it");
        }
    }

    private void checkCanSubmit(ChangeStatus target, Instant now) {
        if (target == ChangeStatus.APPROVED && changeType != ChangeType.STANDARD) {
            throw invalid("Only a standard change is pre-approved; submit this one for approval");
        }
        if (target == ChangeStatus.APPROVAL_PENDING && changeType == ChangeType.STANDARD) {
            throw invalid("A standard change is pre-approved; schedule it instead");
        }
        List<String> missing = missingFromPlan();
        if (!missing.isEmpty()) {
            throw new BusinessRuleException(
                    ErrorCode.CHANGE_PLAN_INCOMPLETE,
                    "Before submitting, the plan needs " + String.join(", ", missing));
        }
        // Only an emergency may be carried out at once; anything else is planned ahead.
        if (changeType != ChangeType.EMERGENCY && !plannedStart.isAfter(now)) {
            throw invalid("The planned window has already started; move it later or raise an emergency change");
        }
    }

    @Override
    protected void onTransition(String from, String to, TransitionInput input, Instant now) {
        switch (ChangeStatus.valueOf(to)) {
            case APPROVAL_PENDING, APPROVED -> {
                if (ChangeStatus.valueOf(from) == ChangeStatus.ASSIGNED) {
                    recordFirstResponse(now);
                }
            }
            case IMPLEMENTING -> implementedAt = now;
            case REVIEW -> markResolved(now);
            case CLOSED, CANCELLED, REJECTED -> markClosed(now);
            default -> {
                // NEW, ASSIGNED: status change only
            }
        }
    }

    @Override
    protected boolean isAssignable() {
        return !isTerminal();
    }

    @Override
    protected void onAssignmentChanged(boolean hadAssignee, boolean hasAssignee, Instant now) {
        if (!hadAssignee && hasAssignee && getStatus() == ChangeStatus.NEW) {
            transition(ChangeStatus.ASSIGNED.name(), TransitionInput.NONE, now);
        } else if (hadAssignee && !hasAssignee && getStatus() == ChangeStatus.ASSIGNED) {
            // A plan nobody owns goes back to the queue. Once submitted, the change keeps its place
            // and needs a new owner before it can start.
            transition(ChangeStatus.NEW.name(), TransitionInput.NONE, now);
        }
    }

    private void applyPlan(ChangePlan plan) {
        if (plan.changeType() == ChangeType.STANDARD && plan.risk() != ChangeRisk.LOW) {
            throw invalid("A standard change is routine and low risk; choose a normal change for this risk");
        }
        if (plan.plannedStart() != null
                && plan.plannedEnd() != null
                && !plan.plannedEnd().isAfter(plan.plannedStart())) {
            throw invalid("The planned window must end after it starts");
        }
        this.changeType = plan.changeType();
        this.risk = plan.risk();
        this.implementationPlan = blankToNull(plan.implementationPlan());
        this.backoutPlan = blankToNull(plan.backoutPlan());
        this.plannedStart = plan.plannedStart();
        this.plannedEnd = plan.plannedEnd();
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException(ErrorCode.CHANGE_PLAN_INVALID, message);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public ChangeStatus getStatus() {
        return ChangeStatus.valueOf(getStatusName());
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public ChangeRisk getRisk() {
        return risk;
    }

    public String getImplementationPlan() {
        return implementationPlan;
    }

    public String getBackoutPlan() {
        return backoutPlan;
    }

    public Instant getPlannedStart() {
        return plannedStart;
    }

    public Instant getPlannedEnd() {
        return plannedEnd;
    }

    public UUID getProblemId() {
        return problemId;
    }

    public Instant getImplementedAt() {
        return implementedAt;
    }

    public ChangeOutcome getOutcome() {
        return outcome;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }
}
