package com.ademola.esm.ticket.change;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.intake.TicketCreatedResponse;
import com.ademola.esm.ticket.intake.TicketIntake;
import com.ademola.esm.ticket.problem.Problem;
import com.ademola.esm.ticket.problem.ProblemRepository;
import com.ademola.esm.user.Role;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raising changes, writing their plans and recording how they went. Status changes, submission
 * included, go through the shared transition endpoint like every other ticket; the approvals module
 * opens an approval when a change is submitted.
 *
 * <p>Changes return nothing: the client reloads the ticket, which keeps this package from depending
 * on the query side (that depends on it, to show a change's details).
 */
@Service
@Transactional
public class ChangeService {

    private static final Logger log = LoggerFactory.getLogger(ChangeService.class);

    private final ChangeRepository changes;
    private final ProblemRepository problems;
    private final TicketIntake intake;
    private final TicketAccessPolicy accessPolicy;
    private final AuditService audit;

    public ChangeService(
            ChangeRepository changes,
            ProblemRepository problems,
            TicketIntake intake,
            TicketAccessPolicy accessPolicy,
            AuditService audit) {
        this.changes = changes;
        this.problems = problems;
        this.intake = intake;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
    }

    /** Support staff raise changes; the raiser is recorded as the requester. */
    @PreAuthorize("hasRole('AGENT')")
    public TicketCreatedResponse create(CurrentUser user, CreateChangeRequest request) {
        // Check the problem before allocating a reference, so a refused link leaves no gap.
        if (request.problemId() != null) {
            Problem problem = problems.findById(request.problemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Problem", request.problemId()));
            if (!problem.isOpen()) {
                throw new BusinessRuleException(
                        ErrorCode.PROBLEM_NOT_OPEN,
                        "%s is %s; a change can only fix an open problem"
                                .formatted(
                                        problem.getReference(),
                                        problem.getStatusName().toLowerCase()));
            }
        }
        var draft = intake.prepare(
                user,
                new TicketIntake.NewTicket(
                        WorkItemType.CHANGE,
                        request.title(),
                        request.description(),
                        request.categoryId(),
                        request.subcategoryId(),
                        request.impact(),
                        request.urgency()));
        Change change = changes.save(new Change(draft, request.plan(), request.problemId()));
        intake.recordCreated(change);
        log.info(
                "Change created ref={} type={} team={}",
                change.getReference(),
                change.getChangeType(),
                change.getAssignedTeamId());
        return TicketCreatedResponse.from(change);
    }

    /** The owning team (or an admin) writes the plan, until the change is submitted. */
    @PreAuthorize("hasRole('AGENT')")
    public void updatePlan(CurrentUser user, UUID changeId, UpdateChangePlanRequest request) {
        Change change = owned(user, changeId);
        StaleVersionException.check("Ticket", request.version(), change.getVersion());

        Map<String, Object> before = plan(change);
        change.updatePlan(request.plan());
        Map<String, Object> after = plan(change);
        if (!before.equals(after)) {
            before.keySet().removeIf(key -> Objects.equals(before.get(key), after.get(key)));
            after.keySet().removeIf(key -> !before.containsKey(key));
            audit.record(AuditRecord.changed(
                    AuditAction.CHANGE_PLAN_UPDATED, AuditEntityType.WORK_ITEM, change.getId(), before, after));
        }
        changes.flush();
    }

    /** The owning team (or an admin) records how the change went. */
    @PreAuthorize("hasRole('AGENT')")
    public void recordOutcome(CurrentUser user, UUID changeId, RecordChangeOutcomeRequest request) {
        Change change = owned(user, changeId);
        StaleVersionException.check("Ticket", request.version(), change.getVersion());

        Map<String, Object> before = review(change);
        change.recordOutcome(request.outcome(), request.notes());
        audit.record(AuditRecord.changed(
                AuditAction.CHANGE_OUTCOME_RECORDED,
                AuditEntityType.WORK_ITEM,
                change.getId(),
                before,
                review(change)));
        changes.flush();
    }

    /** Every member of staff can see a change; only the team carrying it out, or an admin, changes it. */
    private Change owned(CurrentUser user, UUID changeId) {
        Change change = changes.findById(changeId)
                .filter(found -> accessPolicy.canView(user, found))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", changeId));
        if (user.role() != Role.ADMIN
                && !TicketAccessPolicy.isSupporting(user, change, accessPolicy.teamScopeOf(user))) {
            throw new BusinessRuleException(
                    ErrorCode.CHANGE_UPDATE_NOT_PERMITTED, "Only the team carrying out this change can update it");
        }
        return change;
    }

    private static Map<String, Object> plan(Change change) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("changeType", change.getChangeType());
        values.put("risk", change.getRisk());
        values.put("implementationPlan", change.getImplementationPlan());
        values.put("backoutPlan", change.getBackoutPlan());
        values.put("plannedStart", change.getPlannedStart());
        values.put("plannedEnd", change.getPlannedEnd());
        return values;
    }

    private static Map<String, Object> review(Change change) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("outcome", change.getOutcome());
        values.put("reviewNotes", change.getReviewNotes());
        return values;
    }
}
