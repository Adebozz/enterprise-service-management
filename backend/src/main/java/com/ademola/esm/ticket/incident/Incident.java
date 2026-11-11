package com.ademola.esm.ticket.incident;

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
import java.util.Optional;
import java.util.UUID;

/** An unplanned interruption or degradation of a service ("payroll is down"). */
@Entity
@Table(name = "incidents")
@DiscriminatorValue("INCIDENT")
@PrimaryKeyJoinColumn(name = "work_item_id")
public class Incident extends WorkItem {

    @Column(name = "affected_service", length = 120)
    private String affectedService;

    @Column(name = "resolution_code", length = 40)
    private String resolutionCode;

    @Column(name = "resolution_notes", columnDefinition = "text")
    private String resolutionNotes;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    /** The problem that explains this incident (M20), by id like every other cross-aggregate link. */
    @Column(name = "problem_id")
    private UUID problemId;

    protected Incident() {}

    public Incident(WorkItemDraft draft, String affectedService) {
        super(draft, IncidentStatus.NEW.name());
        this.affectedService = affectedService;
    }

    @Override
    public WorkItemType getType() {
        return WorkItemType.INCIDENT;
    }

    @Override
    public WorkflowDefinition<IncidentStatus> workflow() {
        return IncidentWorkflow.DEFINITION;
    }

    @Override
    protected void onTransition(String from, String to, TransitionInput input, Instant now) {
        IncidentStatus previous = IncidentStatus.valueOf(from);
        switch (IncidentStatus.valueOf(to)) {
            case IN_PROGRESS -> {
                if (previous == IncidentStatus.RESOLVED) {
                    reopen();
                }
                recordFirstResponse(now);
            }
            case RESOLVED -> {
                this.resolutionCode =
                        parseResolutionCode(input.resolutionCode()).name();
                this.resolutionNotes = input.notes().trim();
                markResolved(now);
            }
            case CLOSED, CANCELLED -> markClosed(now);
            default -> {
                // NEW, ASSIGNED, WAITING_FOR_USER: status change only
            }
        }
    }

    @Override
    protected boolean isAssignable() {
        return switch (getStatus()) {
            case NEW, ASSIGNED, IN_PROGRESS, WAITING_FOR_USER -> true;
            case RESOLVED, CLOSED, CANCELLED -> false;
        };
    }

    @Override
    protected void onAssignmentChanged(boolean hadAssignee, boolean hasAssignee, Instant now) {
        if (!hadAssignee && hasAssignee && getStatus() == IncidentStatus.NEW) {
            transition(IncidentStatus.ASSIGNED.name(), TransitionInput.NONE, now);
        } else if (hadAssignee && !hasAssignee && getStatus() != IncidentStatus.NEW) {
            // ASSIGNED -> NEW (unassign), IN_PROGRESS/WAITING_FOR_USER -> NEW (return to queue)
            transition(IncidentStatus.NEW.name(), TransitionInput.NONE, now);
        }
    }

    @Override
    public Optional<String> statusAfterRequesterReply() {
        return getStatus() == IncidentStatus.WAITING_FOR_USER
                ? Optional.of(IncidentStatus.IN_PROGRESS.name())
                : Optional.empty();
    }

    /** The previous resolution didn't hold: forget it (the audit trail keeps the history). */
    private void reopen() {
        reopenCount++;
        resolutionCode = null;
        resolutionNotes = null;
        clearResolved();
    }

    private static ResolutionCode parseResolutionCode(String code) {
        try {
            return ResolutionCode.valueOf(code.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(
                    ErrorCode.TRANSITION_REQUIREMENT_MISSING,
                    "Unknown resolution code '%s'. Allowed: %s"
                            .formatted(code, java.util.Arrays.toString(ResolutionCode.values())));
        }
    }

    /**
     * Links or unlinks the problem behind this incident. Whether the problem can take incidents is
     * the problem's rule, checked by the caller; any incident may be linked, even a closed one, so
     * past incidents can be counted against the problem that caused them.
     */
    public void linkProblem(UUID newProblemId) {
        this.problemId = newProblemId;
    }

    public UUID getProblemId() {
        return problemId;
    }

    public IncidentStatus getStatus() {
        return IncidentStatus.valueOf(getStatusName());
    }

    public String getAffectedService() {
        return affectedService;
    }

    public String getResolutionCode() {
        return resolutionCode;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public int getReopenCount() {
        return reopenCount;
    }
}
