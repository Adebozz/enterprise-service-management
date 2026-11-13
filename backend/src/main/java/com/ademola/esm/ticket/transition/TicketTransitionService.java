package com.ademola.esm.ticket.transition;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.ticket.TicketChangedEvent;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemRepository;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.comment.CommentService;
import com.ademola.esm.ticket.query.TicketQueryService;
import com.ademola.esm.ticket.query.TicketResponse;
import com.ademola.esm.ticket.workflow.Actor;
import com.ademola.esm.ticket.workflow.Requirement;
import com.ademola.esm.ticket.workflow.Transition;
import com.ademola.esm.ticket.workflow.TransitionInput;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Status changes requested by users. Order of checks:
 *
 * <ol>
 *   <li>Can the caller see the ticket at all? If not: 404 (don't reveal it exists).
 *   <li>Is their copy current? If not: 409 (reload; the situation may have changed).
 *   <li>Is the move part of the lifecycle? If not: 409 INVALID_STATUS_TRANSITION.
 *   <li>May this caller make it? If not: 403 TRANSITION_NOT_PERMITTED.
 *   <li>Requirements and side effects are enforced by the entity itself.
 * </ol>
 */
@Service
@Transactional
public class TicketTransitionService {

    private static final Logger log = LoggerFactory.getLogger(TicketTransitionService.class);

    private final WorkItemRepository workItems;
    private final TicketAccessPolicy accessPolicy;
    private final TicketQueryService queries;
    private final AuditService audit;
    private final CommentService comments;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public TicketTransitionService(
            WorkItemRepository workItems,
            TicketAccessPolicy accessPolicy,
            TicketQueryService queries,
            AuditService audit,
            CommentService comments,
            ApplicationEventPublisher events,
            Clock clock) {
        this.workItems = workItems;
        this.accessPolicy = accessPolicy;
        this.queries = queries;
        this.audit = audit;
        this.comments = comments;
        this.events = events;
        this.clock = clock;
    }

    @PreAuthorize("isAuthenticated()")
    public TicketResponse transition(CurrentUser user, UUID ticketId, TransitionRequest request) {
        Set<UUID> teamScope = accessPolicy.teamScopeOf(user);
        WorkItem item = visibleTicket(user, ticketId, teamScope);
        StaleVersionException.check("Ticket", request.version(), item.getVersion());

        Transition<?> transition = item.workflow().require(item.getStatusName(), request.targetStatus());
        if (!transition.isAllowedFor(TicketActors.of(user, item, teamScope))) {
            throw new BusinessRuleException(
                    ErrorCode.TRANSITION_NOT_PERMITTED,
                    "You are not allowed to '%s' this %s"
                            .formatted(
                                    transition.label(),
                                    item.workflow().subject().toLowerCase()));
        }

        String from = item.getStatusName();
        TransitionInput input = new TransitionInput(
                request.reason(),
                request.resolutionCode() == null
                        ? null
                        : request.resolutionCode().name(),
                request.notes());
        Instant now = clock.instant();
        item.transition(request.targetStatus(), input, now);

        audit.record(new AuditRecord(
                AuditAction.TICKET_STATUS_CHANGED,
                AuditEntityType.WORK_ITEM,
                item.getId(),
                Map.of("status", from),
                Map.of("status", item.getStatusName()),
                metadata(transition, input)));
        events.publishEvent(TicketChangedEvent.changed(item, now)); // after the audit entry it explains
        if (input.reason() != null && !input.reason().isBlank()) {
            // Make the reason part of the visible conversation ("Which floor are you on?").
            comments.recordStatusNote(item, user.id(), input.reason(), item.getStatusName());
        }
        log.info("Ticket {} {} -> {} by {}", item.getReference(), from, item.getStatusName(), user.id());

        // Flush so the @Version check runs now (a concurrent change surfaces here as a 409) and the
        // response carries the new version.
        workItems.flush();
        return queries.get(user, ticketId);
    }

    /**
     * A move only the system may make (approval outcomes, for example), on behalf of another module
     * that has already decided it is allowed. Audited with the given trigger; a note, when present,
     * is posted as a public comment by {@code noteAuthorId}, like a transition reason.
     *
     * @throws com.ademola.esm.ticket.workflow.InvalidTransitionException (409) if the ticket has
     *     meanwhile moved somewhere this transition doesn't start from, e.g. it was cancelled
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applySystemTransition(
            UUID ticketId, String targetStatus, String trigger, UUID noteAuthorId, String note) {
        WorkItem item =
                workItems.findById(ticketId).orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
        Transition<?> transition = item.workflow().require(item.getStatusName(), targetStatus);
        if (!transition.isAllowedFor(Set.of(Actor.SYSTEM))) {
            throw new IllegalStateException("'" + transition.label() + "' is not a system transition");
        }
        String from = item.getStatusName();
        Instant now = clock.instant();
        item.transition(targetStatus, TransitionInput.NONE, now);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transition", transition.label());
        metadata.put("trigger", trigger);
        audit.record(new AuditRecord(
                AuditAction.TICKET_STATUS_CHANGED,
                AuditEntityType.WORK_ITEM,
                item.getId(),
                Map.of("status", from),
                Map.of("status", item.getStatusName()),
                metadata));
        events.publishEvent(TicketChangedEvent.changed(item, now));
        if (note != null && !note.isBlank()) {
            comments.recordStatusNote(item, noteAuthorId, note.trim(), item.getStatusName());
        }
        log.info("Ticket {} {} -> {} ({})", item.getReference(), from, item.getStatusName(), trigger);
        workItems.flush();
    }

    /** The moves the caller may make now. Drives the UI's buttons, so rules live only here. */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<AvailableTransition> available(CurrentUser user, UUID ticketId) {
        Set<UUID> teamScope = accessPolicy.teamScopeOf(user);
        WorkItem item = visibleTicket(user, ticketId, teamScope);
        Set<Actor> actors = TicketActors.of(user, item, teamScope);
        boolean hasAssignee = item.getAssigneeId() != null;
        return item.workflow().transitionsFrom(item.getStatusName()).stream()
                .filter(t ->
                        t.isAllowedFor(actors) && t.isSatisfiableBy(hasAssignee) && item.offersMoveTo(t.to().name()))
                .map(t -> new AvailableTransition(t.to().name(), t.label(), inputRequirements(t)))
                .toList();
    }

    private WorkItem visibleTicket(CurrentUser user, UUID ticketId, Set<UUID> teamScope) {
        return workItems
                .findById(ticketId)
                .filter(item -> TicketAccessPolicy.canView(user, item, teamScope))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
    }

    /** ASSIGNEE is about ticket state, not something the client sends, so it isn't listed. */
    private static Set<Requirement> inputRequirements(Transition<?> transition) {
        return transition.requirements().stream()
                .filter(r -> r != Requirement.ASSIGNEE)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<String, Object> metadata(Transition<?> transition, TransitionInput input) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transition", transition.label());
        if (input.reason() != null && !input.reason().isBlank()) {
            metadata.put("reason", input.reason().trim());
        }
        if (input.resolutionCode() != null && !input.resolutionCode().isBlank()) {
            metadata.put("resolutionCode", input.resolutionCode().trim());
        }
        return metadata;
    }
}
