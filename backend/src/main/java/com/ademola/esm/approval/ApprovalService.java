package com.ademola.esm.approval;

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
import com.ademola.esm.ticket.TicketChangedEvent;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemRepository;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.query.NamedRef;
import com.ademola.esm.ticket.request.ServiceRequestStatus;
import com.ademola.esm.ticket.transition.TicketTransitionService;
import com.ademola.esm.user.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-off for service requests that need it. The request waits in APPROVAL_PENDING (its SLA
 * clocks paused) until a team lead of its team, or an admin, approves or rejects it. Status moves
 * are SYSTEM transitions, so nobody can approve through the ordinary transitions endpoint.
 */
@Service
@Transactional
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    static final String AWAITING = ServiceRequestStatus.APPROVAL_PENDING.name();

    private final ApprovalRepository approvals;
    private final WorkItemRepository workItems;
    private final TicketAccessPolicy accessPolicy;
    private final TicketTransitionService transitions;
    private final TeamService teams;
    private final UserService users;
    private final AuditService audit;
    private final Clock clock;

    ApprovalService(
            ApprovalRepository approvals,
            WorkItemRepository workItems,
            TicketAccessPolicy accessPolicy,
            TicketTransitionService transitions,
            TeamService teams,
            UserService users,
            AuditService audit,
            Clock clock) {
        this.approvals = approvals;
        this.workItems = workItems;
        this.accessPolicy = accessPolicy;
        this.transitions = transitions;
        this.teams = teams;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    /** Puts a just-raised request on hold until someone approves it. Part of the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireApproval(UUID requestId) {
        Approval approval = approvals.save(new Approval(requestId));
        transitions.applySystemTransition(requestId, AWAITING, "APPROVAL_REQUIRED", null, null);
        audit.record(AuditRecord.created(
                AuditAction.APPROVAL_REQUESTED,
                AuditEntityType.APPROVAL,
                approval.getId(),
                Map.of("workItemId", requestId)));
    }

    /** Pending approvals the caller may decide, oldest first. */
    @PreAuthorize("hasRole('TEAM_LEAD')")
    @Transactional(readOnly = true)
    public List<PendingApprovalResponse> pendingFor(CurrentUser user) {
        Set<UUID> userTeams = accessPolicy.teamScopeOf(user);
        List<Approval> pending = approvals.findByStatusOrderByCreatedAtAsc(ApprovalStatus.PENDING);
        Map<UUID, WorkItem> items =
                workItems
                        .findAllById(
                                pending.stream().map(Approval::getWorkItemId).toList())
                        .stream()
                        .collect(Collectors.toMap(WorkItem::getId, Function.identity()));
        List<Approval> mine = pending.stream()
                .filter(a -> {
                    WorkItem item = items.get(a.getWorkItemId());
                    return ApprovalPolicy.canDecide(user, item.getRequesterId(), item.getAssignedTeamId(), userTeams);
                })
                .toList();

        Set<UUID> people = new HashSet<>();
        Set<UUID> teamIds = new HashSet<>();
        mine.forEach(a -> {
            people.add(items.get(a.getWorkItemId()).getRequesterId());
            teamIds.add(items.get(a.getWorkItemId()).getAssignedTeamId());
        });
        Map<UUID, String> names = users.displayNamesOf(people);
        Map<UUID, String> teamNames = teams.namesOf(teamIds);
        return mine.stream()
                .map(a -> {
                    WorkItem item = items.get(a.getWorkItemId());
                    return new PendingApprovalResponse(
                            a.getId(),
                            a.getVersion(),
                            a.getCreatedAt(),
                            item.getId(),
                            item.getReference(),
                            item.getTitle(),
                            item.getDescription(),
                            item.getPriority(),
                            new NamedRef(item.getRequesterId(), names.get(item.getRequesterId())),
                            new NamedRef(item.getAssignedTeamId(), teamNames.get(item.getAssignedTeamId())));
                })
                .toList();
    }

    /** The approval of a ticket, for anyone who can see the ticket. 404 if it needs none. */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ApprovalResponse forTicket(CurrentUser user, UUID ticketId) {
        Set<UUID> userTeams = accessPolicy.teamScopeOf(user);
        WorkItem item = workItems
                .findById(ticketId)
                .filter(found -> TicketAccessPolicy.canView(user, found, userTeams))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
        Approval approval = approvals
                .findByWorkItemId(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Approval for ticket", ticketId));
        return response(approval, item, user, userTeams);
    }

    /**
     * Approves or rejects. Checks, in order: may the caller see the request (else 404), may they
     * decide it (403), is their copy current (409), is it still pending (409), is a rejection
     * explained (422). The decision and the ticket's status change commit together.
     */
    @PreAuthorize("isAuthenticated()")
    public ApprovalResponse decide(CurrentUser user, UUID approvalId, ApprovalDecisionRequest request) {
        Set<UUID> userTeams = accessPolicy.teamScopeOf(user);
        Approval approval =
                approvals.findById(approvalId).orElseThrow(() -> new ResourceNotFoundException("Approval", approvalId));
        WorkItem item = workItems
                .findById(approval.getWorkItemId())
                .filter(found -> TicketAccessPolicy.canView(user, found, userTeams))
                .orElseThrow(() -> new ResourceNotFoundException("Approval", approvalId));
        if (!ApprovalPolicy.canDecide(user, item.getRequesterId(), item.getAssignedTeamId(), userTeams)) {
            throw new BusinessRuleException(
                    ErrorCode.APPROVAL_NOT_PERMITTED,
                    user.id().equals(item.getRequesterId())
                            ? "You can't approve your own request"
                            : "Only the leads of the team handling this request, or an admin, can decide it");
        }
        StaleVersionException.check("Approval", request.version(), approval.getVersion());

        Instant now = clock.instant();
        String target;
        if (request.decision() == ApprovalDecisionRequest.Decision.APPROVE) {
            approval.approve(user.id(), now, request.comment());
            target = ServiceRequestStatus.APPROVED.name();
        } else {
            approval.reject(user.id(), now, request.comment());
            target = ServiceRequestStatus.REJECTED.name();
        }
        transitions.applySystemTransition(item.getId(), target, "APPROVAL", user.id(), approval.getComment());
        audit.record(new AuditRecord(
                AuditAction.APPROVAL_DECIDED,
                AuditEntityType.APPROVAL,
                approval.getId(),
                Map.of("status", ApprovalStatus.PENDING),
                Map.of("status", approval.getStatus()),
                Map.of("workItemId", item.getId())));
        approvals.flush(); // the @Version check runs now, and the response carries the new version
        log.info("Approval for {} {} by {}", item.getReference(), approval.getStatus(), user.id());
        return response(approval, item, user, userTeams);
    }

    /**
     * A request that leaves "awaiting approval" without a decision (the requester cancelled it)
     * no longer needs one, so it leaves every approver's inbox.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(TicketChangedEvent event) {
        if (event.created() || event.type() != WorkItemType.SERVICE_REQUEST || AWAITING.equals(event.status())) {
            return;
        }
        approvals.findByWorkItemId(event.ticketId()).filter(Approval::isPending).ifPresent(approval -> {
            approval.withdraw(event.occurredAt());
            audit.record(AuditRecord.event(
                    AuditAction.APPROVAL_WITHDRAWN,
                    AuditEntityType.APPROVAL,
                    approval.getId(),
                    Map.of("workItemId", event.ticketId(), "ticketStatus", event.status())));
        });
    }

    private ApprovalResponse response(Approval approval, WorkItem item, CurrentUser user, Set<UUID> userTeams) {
        NamedRef decidedBy = approval.getDecidedBy() == null
                ? null
                : new NamedRef(
                        approval.getDecidedBy(),
                        users.displayNamesOf(Set.of(approval.getDecidedBy())).get(approval.getDecidedBy()));
        return new ApprovalResponse(
                approval.getId(),
                approval.getStatus(),
                approval.getCreatedAt(),
                decidedBy,
                approval.getDecidedAt(),
                approval.getComment(),
                approval.isPending()
                        && ApprovalPolicy.canDecide(user, item.getRequesterId(), item.getAssignedTeamId(), userTeams),
                approval.getVersion());
    }
}
