package com.ademola.esm.ticket.comment;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.ticket.TicketChangedEvent;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemRepository;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.workflow.TransitionInput;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CommentService {

    private final CommentRepository comments;
    private final WorkItemRepository workItems;
    private final TicketAccessPolicy accessPolicy;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CommentService(
            CommentRepository comments,
            WorkItemRepository workItems,
            TicketAccessPolicy accessPolicy,
            AuditService audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.comments = comments;
        this.workItems = workItems;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<CommentResponse> thread(CurrentUser user, UUID ticketId) {
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        WorkItem item = visibleTicket(user, ticketId, teams);
        return comments.findThread(item.getId(), CommentPolicy.readableBy(user, item, teams));
    }

    @PreAuthorize("isAuthenticated()")
    public CommentResponse add(CurrentUser user, UUID ticketId, AddCommentRequest request) {
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        WorkItem item = visibleTicket(user, ticketId, teams);
        if (!CommentPolicy.canWrite(user, item, teams, request.visibility())) {
            throw new BusinessRuleException(
                    ErrorCode.COMMENT_NOT_PERMITTED, "Only support staff handling this ticket can add internal notes");
        }
        if (item.isTerminal()) {
            throw new BusinessRuleException(
                    ErrorCode.TICKET_CLOSED,
                    "%s is %s; it no longer accepts comments"
                            .formatted(item.getReference(), item.getStatusName().toLowerCase()));
        }
        Instant now = clock.instant();
        Comment comment = save(item, user.id(), request.visibility(), request.body(), null, now);
        if (CommentPolicy.countsAsFirstResponse(user, item, teams, request.visibility())
                && workItems.recordFirstResponseIfAbsent(item.getId(), now) == 1) {
            events.publishEvent(TicketChangedEvent.firstResponse(item, now));
        }
        if (request.visibility() == CommentVisibility.PUBLIC && user.id().equals(item.getRequesterId())) {
            resumeOnRequesterReply(item, now);
        }
        return new CommentResponse(
                comment.getId(),
                user.id(),
                user.displayName(),
                comment.getVisibility(),
                comment.getBody(),
                null,
                comment.getCreatedAt());
    }

    /**
     * Records the reason given for a status change as a public comment, so the requester sees
     * e.g. what information support is waiting for. Called inside the transition's transaction,
     * after it has been authorized, so it applies no checks of its own (and works even when the
     * new status is terminal, such as a cancellation reason).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordStatusNote(WorkItem item, UUID authorId, String reason, String newStatus) {
        save(item, authorId, CommentVisibility.PUBLIC, reason, newStatus, clock.instant());
    }

    /**
     * Posts a staff-only note written on the system's behalf by {@code authorId} (a fixed problem
     * telling its incidents, for example). The caller has decided it is allowed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void recordInternalNote(WorkItem item, UUID authorId, String body) {
        save(item, authorId, CommentVisibility.INTERNAL, body, null, clock.instant());
    }

    /**
     * The requester answered while support was waiting for them, so the ticket goes back to work
     * (and its SLA clock resumes). Requesters never count as first responders, so the direct
     * first-response update can't be overwritten by this entity change.
     */
    private void resumeOnRequesterReply(WorkItem item, Instant now) {
        item.statusAfterRequesterReply()
                .map(target -> item.workflow().require(item.getStatusName(), target))
                .filter(transition -> transition.isSatisfiableBy(item.getAssigneeId() != null))
                .ifPresent(transition -> {
                    String from = item.getStatusName();
                    item.transition(transition.to().name(), TransitionInput.NONE, now);
                    audit.record(new AuditRecord(
                            AuditAction.TICKET_STATUS_CHANGED,
                            AuditEntityType.WORK_ITEM,
                            item.getId(),
                            Map.of("status", from),
                            Map.of("status", item.getStatusName()),
                            Map.of("transition", transition.label(), "trigger", "REQUESTER_REPLY")));
                    events.publishEvent(TicketChangedEvent.changed(item, now));
                });
    }

    private Comment save(
            WorkItem item,
            UUID authorId,
            CommentVisibility visibility,
            String body,
            String relatedStatus,
            Instant now) {
        Comment comment = comments.save(new Comment(item.getId(), authorId, visibility, body, relatedStatus, now));
        // The body lives in the comments table; the audit entry records that it happened.
        audit.record(AuditRecord.event(
                AuditAction.COMMENT_ADDED,
                AuditEntityType.WORK_ITEM,
                item.getId(),
                relatedStatus == null
                        ? Map.of("commentId", comment.getId(), "visibility", visibility)
                        : Map.of(
                                "commentId",
                                comment.getId(),
                                "visibility",
                                visibility,
                                "relatedStatus",
                                relatedStatus)));
        return comment;
    }

    private WorkItem visibleTicket(CurrentUser user, UUID ticketId, Set<UUID> teams) {
        return workItems
                .findById(ticketId)
                .filter(item -> TicketAccessPolicy.canView(user, item, teams))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
    }
}
