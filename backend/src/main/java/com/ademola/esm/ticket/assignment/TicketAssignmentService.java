package com.ademola.esm.ticket.assignment;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.team.TeamSummary;
import com.ademola.esm.ticket.AssignmentChange;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemRepository;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.query.NamedRef;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserService;
import java.time.Clock;
import java.util.HashMap;
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
 * Changes who owns a ticket: take, release, assign, reassign, transfer. Order of checks mirrors
 * transitions: visible (404) -> current version (409) -> permitted (403) -> valid target (422) ->
 * ticket in an assignable state (409, enforced by the entity).
 */
@Service
@Transactional
public class TicketAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(TicketAssignmentService.class);

    private final WorkItemRepository workItems;
    private final TicketAccessPolicy accessPolicy;
    private final TeamService teams;
    private final UserService users;
    private final AuditService audit;
    private final Clock clock;

    public TicketAssignmentService(
            WorkItemRepository workItems,
            TicketAccessPolicy accessPolicy,
            TeamService teams,
            UserService users,
            AuditService audit,
            Clock clock) {
        this.workItems = workItems;
        this.accessPolicy = accessPolicy;
        this.teams = teams;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    /** Requesters never change ownership, even of their own tickets. */
    @PreAuthorize("hasRole('AGENT')")
    public AssignmentResponse assign(CurrentUser user, UUID ticketId, AssignmentRequest request) {
        Set<UUID> teamScope = accessPolicy.teamScopeOf(user);
        WorkItem item = workItems
                .findById(ticketId)
                .filter(found -> TicketAccessPolicy.canView(user, found, teamScope))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
        StaleVersionException.check("Ticket", request.version(), item.getVersion());

        boolean unchanged = request.teamId().equals(item.getAssignedTeamId())
                && Objects.equals(request.assigneeId(), item.getAssigneeId());
        if (unchanged) {
            return response(item); // idempotent: no version bump, no audit noise
        }

        AssignmentPolicy.check(user, teamScope, item, request.teamId(), request.assigneeId());
        teams.requireActiveTeam(request.teamId());
        if (request.assigneeId() != null) {
            requireEligibleAssignee(request.assigneeId(), request.teamId());
        }

        AssignmentChange change = item.assign(request.teamId(), request.assigneeId(), clock.instant());
        audit.record(AuditRecord.changed(
                AuditAction.TICKET_ASSIGNMENT_CHANGED,
                AuditEntityType.WORK_ITEM,
                item.getId(),
                snapshot(change.before()),
                snapshot(change.after())));
        log.info(
                "Ticket {} assignment team {} -> {}, assignee {} -> {} by {}",
                item.getReference(),
                change.before().teamId(),
                change.after().teamId(),
                change.before().assigneeId(),
                change.after().assigneeId(),
                user.id());

        workItems.flush(); // @Version check now (concurrent assignment -> 409) and fresh version
        return response(item);
    }

    private void requireEligibleAssignee(UUID assigneeId, UUID teamId) {
        User assignee = users.require(assigneeId);
        boolean eligible = assignee.isActive()
                && assignee.getRole().isStaff()
                && teams.teamIdsOf(assigneeId).contains(teamId);
        if (!eligible) {
            throw new BusinessRuleException(
                    ErrorCode.ASSIGNEE_NOT_IN_TEAM, "The assignee must be an active member of the assigned team");
        }
    }

    private AssignmentResponse response(WorkItem item) {
        TeamSummary team = teams.summaryOf(item.getAssignedTeamId());
        NamedRef assignee = null;
        if (item.getAssigneeId() != null) {
            User user = users.require(item.getAssigneeId());
            assignee = new NamedRef(user.getId(), user.getDisplayName());
        }
        return new AssignmentResponse(
                item.getId(),
                item.getReference(),
                item.getStatusName(),
                new NamedRef(team.id(), team.name()),
                assignee,
                item.getVersion());
    }

    private static Map<String, Object> snapshot(AssignmentChange.Snapshot snapshot) {
        Map<String, Object> values = new HashMap<>(); // allows null assignee
        values.put("teamId", snapshot.teamId());
        values.put("assigneeId", snapshot.assigneeId());
        values.put("status", snapshot.status());
        return values;
    }
}
