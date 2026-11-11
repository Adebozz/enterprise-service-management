package com.ademola.esm.ticket.problem;

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
import com.ademola.esm.ticket.incident.Incident;
import com.ademola.esm.ticket.incident.IncidentRepository;
import com.ademola.esm.ticket.intake.TicketCreatedResponse;
import com.ademola.esm.ticket.intake.TicketIntake;
import com.ademola.esm.user.Role;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raising problems, recording what the investigation found, and linking the incidents a problem
 * explains. Status changes go through the shared transition endpoint like every other ticket.
 *
 * <p>Changes return nothing: the client reloads the ticket, which keeps this package from depending
 * on the query side (that depends on it, to show a problem's details).
 */
@Service
@Transactional
public class ProblemService {

    private static final Logger log = LoggerFactory.getLogger(ProblemService.class);

    private final ProblemRepository problems;
    private final IncidentRepository incidents;
    private final TicketIntake intake;
    private final TicketAccessPolicy accessPolicy;
    private final AuditService audit;

    public ProblemService(
            ProblemRepository problems,
            IncidentRepository incidents,
            TicketIntake intake,
            TicketAccessPolicy accessPolicy,
            AuditService audit) {
        this.problems = problems;
        this.incidents = incidents;
        this.intake = intake;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
    }

    /** Support staff raise problems; the raiser is recorded as the requester. */
    @PreAuthorize("hasRole('AGENT')")
    public TicketCreatedResponse create(CurrentUser user, CreateProblemRequest request) {
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        // Check the incident before allocating a reference, so a refused link leaves no gap.
        Incident incident = request.incidentId() == null ? null : linkableIncident(user, request.incidentId(), teams);
        var draft = intake.prepare(
                user,
                new TicketIntake.NewTicket(
                        WorkItemType.PROBLEM,
                        request.title(),
                        request.description(),
                        request.categoryId(),
                        request.subcategoryId(),
                        request.impact(),
                        request.urgency()));
        Problem problem = problems.save(new Problem(draft));
        intake.recordCreated(problem);
        if (incident != null) {
            link(incident, problem);
        }
        log.info("Problem created ref={} team={}", problem.getReference(), problem.getAssignedTeamId());
        return TicketCreatedResponse.from(problem);
    }

    /** The owning team (or an admin) records the root cause and workaround as they become known. */
    @PreAuthorize("hasRole('AGENT')")
    public void updateAnalysis(CurrentUser user, UUID problemId, UpdateProblemAnalysisRequest request) {
        Problem problem =
                problems.findById(problemId).orElseThrow(() -> new ResourceNotFoundException("Ticket", problemId));
        if (!owns(user, problem, accessPolicy.teamScopeOf(user))) {
            throw new BusinessRuleException(
                    ErrorCode.PROBLEM_UPDATE_NOT_PERMITTED,
                    "Only the team working on this problem can change its analysis");
        }
        StaleVersionException.check("Ticket", request.version(), problem.getVersion());

        Map<String, Object> before = analysis(problem);
        problem.updateAnalysis(request.rootCause(), request.workaround());
        Map<String, Object> after = analysis(problem);
        if (!before.equals(after)) {
            before.keySet().removeIf(key -> Objects.equals(before.get(key), after.get(key)));
            after.keySet().removeIf(key -> !before.containsKey(key));
            audit.record(AuditRecord.changed(
                    AuditAction.PROBLEM_ANALYSIS_UPDATED, AuditEntityType.WORK_ITEM, problem.getId(), before, after));
        }
        problems.flush();
    }

    /** Staff handling the incident, or the problem, may say which problem explains the incident. */
    @PreAuthorize("hasRole('AGENT')")
    public void linkIncident(CurrentUser user, UUID incidentId, LinkProblemRequest request) {
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        Incident incident = visibleIncident(user, incidentId, teams);
        StaleVersionException.check("Ticket", request.version(), incident.getVersion());
        Problem problem = problems.findById(request.problemId())
                .orElseThrow(() -> new ResourceNotFoundException("Problem", request.problemId()));
        if (!TicketAccessPolicy.canSeeStaffDetails(user, incident, teams) && !owns(user, problem, teams)) {
            throw notPermitted();
        }
        if (problem.getId().equals(incident.getProblemId())) {
            return;
        }
        link(incident, problem);
        incidents.flush();
    }

    @PreAuthorize("hasRole('AGENT')")
    public void unlinkIncident(CurrentUser user, UUID incidentId, long version) {
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        Incident incident = visibleIncident(user, incidentId, teams);
        StaleVersionException.check("Ticket", version, incident.getVersion());
        if (incident.getProblemId() == null) {
            return;
        }
        Problem problem = problems.getReferenceById(incident.getProblemId());
        if (!TicketAccessPolicy.canSeeStaffDetails(user, incident, teams) && !owns(user, problem, teams)) {
            throw notPermitted();
        }
        incident.linkProblem(null);
        audit.record(AuditRecord.changed(
                AuditAction.INCIDENT_PROBLEM_UNLINKED,
                AuditEntityType.WORK_ITEM,
                incident.getId(),
                Map.of("problemReference", problem.getReference()),
                Map.of()));
        incidents.flush();
    }

    private void link(Incident incident, Problem problem) {
        if (!problem.isOpen()) {
            throw new BusinessRuleException(
                    ErrorCode.PROBLEM_NOT_OPEN,
                    "%s is %s; link the incident to an open problem"
                            .formatted(
                                    problem.getReference(),
                                    problem.getStatusName().toLowerCase()));
        }
        Map<String, Object> before = new LinkedHashMap<>();
        if (incident.getProblemId() != null) {
            before.put(
                    "problemReference",
                    problems.getReferenceById(incident.getProblemId()).getReference());
        }
        incident.linkProblem(problem.getId());
        audit.record(AuditRecord.changed(
                AuditAction.INCIDENT_PROBLEM_LINKED,
                AuditEntityType.WORK_ITEM,
                incident.getId(),
                before,
                Map.of("problemReference", problem.getReference())));
    }

    /** For linking at creation: the incident must be visible and the caller must handle it. */
    private Incident linkableIncident(CurrentUser user, UUID incidentId, Set<UUID> teams) {
        Incident incident = visibleIncident(user, incidentId, teams);
        if (!TicketAccessPolicy.canSeeStaffDetails(user, incident, teams)) {
            throw notPermitted();
        }
        return incident;
    }

    /** An incident the caller can't see is reported as not found, like everywhere else. */
    private Incident visibleIncident(CurrentUser user, UUID incidentId, Set<UUID> teams) {
        return incidents
                .findById(incidentId)
                .filter(incident -> TicketAccessPolicy.canView(user, incident, teams))
                .orElseThrow(() -> new ResourceNotFoundException("Incident", incidentId));
    }

    /** The team working on the problem, its assignee, or an admin. */
    private static boolean owns(CurrentUser user, Problem problem, Set<UUID> teams) {
        return user.role() == Role.ADMIN || TicketAccessPolicy.isSupporting(user, problem, teams);
    }

    private static Map<String, Object> analysis(Problem problem) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rootCause", problem.getRootCause());
        values.put("workaround", problem.getWorkaround());
        return values;
    }

    private static BusinessRuleException notPermitted() {
        return new BusinessRuleException(
                ErrorCode.INCIDENT_LINK_NOT_PERMITTED, "Only staff handling the incident or the problem can link them");
    }
}
