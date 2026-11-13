package com.ademola.esm.ticket.problem;

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
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The unknown or known cause of one or more incidents ("VPN drops every afternoon"). Incidents get
 * people working again; a problem stops the incidents coming back.
 */
@Entity
@Table(name = "problems")
@DiscriminatorValue("PROBLEM")
@PrimaryKeyJoinColumn(name = "work_item_id")
public class Problem extends WorkItem {

    @Column(name = "root_cause", columnDefinition = "text")
    private String rootCause;

    @Column(columnDefinition = "text")
    private String workaround;

    @Column(name = "known_error_at")
    private Instant knownErrorAt;

    @Column(name = "fix_notes", columnDefinition = "text")
    private String fixNotes;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    protected Problem() {}

    public Problem(WorkItemDraft draft) {
        super(draft, ProblemStatus.NEW.name());
    }

    @Override
    public WorkItemType getType() {
        return WorkItemType.PROBLEM;
    }

    @Override
    public WorkflowDefinition<ProblemStatus> workflow() {
        return ProblemWorkflow.DEFINITION;
    }

    /** Whether the investigation can still change: not once it is resolved, closed or cancelled. */
    public boolean isOpen() {
        return switch (getStatus()) {
            case NEW, ASSIGNED, INVESTIGATING, KNOWN_ERROR -> true;
            case RESOLVED, CLOSED, CANCELLED -> false;
        };
    }

    /**
     * Records what is known so far. Blank values clear a field, except that a known error must keep
     * both its root cause and its workaround: that is what makes it a known error.
     */
    public void updateAnalysis(String newRootCause, String newWorkaround) {
        if (!isOpen()) {
            throw new BusinessRuleException(
                    ErrorCode.PROBLEM_NOT_OPEN, "A problem in status %s can't be changed".formatted(getStatus()));
        }
        String cause = blankToNull(newRootCause);
        String around = blankToNull(newWorkaround);
        if (getStatus() == ProblemStatus.KNOWN_ERROR && (cause == null || around == null)) {
            throw incomplete("A known error must keep its root cause and workaround");
        }
        this.rootCause = cause;
        this.workaround = around;
    }

    @Override
    protected void checkCanEnter(String targetStatus, Instant now) {
        switch (ProblemStatus.valueOf(targetStatus)) {
            case KNOWN_ERROR -> {
                if (rootCause == null || workaround == null) {
                    throw incomplete("Record the root cause and a workaround before marking a known error");
                }
            }
            case RESOLVED -> {
                if (rootCause == null) {
                    throw incomplete("Record the root cause before resolving the problem");
                }
            }
            default -> {
                // nothing to check
            }
        }
    }

    @Override
    protected void onTransition(String from, String to, TransitionInput input, Instant now) {
        switch (ProblemStatus.valueOf(to)) {
            case INVESTIGATING -> {
                if (ProblemStatus.valueOf(from) == ProblemStatus.RESOLVED) {
                    reopen();
                }
                recordFirstResponse(now);
            }
            case KNOWN_ERROR -> {
                if (knownErrorAt == null) {
                    knownErrorAt = now;
                }
            }
            case RESOLVED -> {
                fixNotes = input.notes().trim();
                markResolved(now);
            }
            case CLOSED, CANCELLED -> markClosed(now);
            default -> {
                // NEW, ASSIGNED: status change only
            }
        }
    }

    @Override
    protected boolean isAssignable() {
        return isOpen();
    }

    @Override
    protected void onAssignmentChanged(boolean hadAssignee, boolean hasAssignee, Instant now) {
        if (!hadAssignee && hasAssignee && getStatus() == ProblemStatus.NEW) {
            transition(ProblemStatus.ASSIGNED.name(), TransitionInput.NONE, now);
        } else if (hadAssignee && !hasAssignee && getStatus() != ProblemStatus.NEW) {
            // An investigation always has an owner; without one the problem goes back to the queue.
            transition(ProblemStatus.NEW.name(), TransitionInput.NONE, now);
        }
    }

    /** The fix didn't hold. The analysis stays (it is usually still right); the fix is forgotten. */
    private void reopen() {
        reopenCount++;
        fixNotes = null;
        clearResolved();
    }

    private static BusinessRuleException incomplete(String message) {
        return new BusinessRuleException(ErrorCode.PROBLEM_ANALYSIS_INCOMPLETE, message);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public ProblemStatus getStatus() {
        return ProblemStatus.valueOf(getStatusName());
    }

    public String getRootCause() {
        return rootCause;
    }

    public String getWorkaround() {
        return workaround;
    }

    public Instant getKnownErrorAt() {
        return knownErrorAt;
    }

    public String getFixNotes() {
        return fixNotes;
    }

    public int getReopenCount() {
        return reopenCount;
    }
}
