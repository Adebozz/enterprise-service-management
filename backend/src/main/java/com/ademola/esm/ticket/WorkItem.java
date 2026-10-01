package com.ademola.esm.ticket;

import com.ademola.esm.common.persistence.BaseEntity;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.priority.Urgency;
import com.ademola.esm.ticket.workflow.Transition;
import com.ademola.esm.ticket.workflow.TransitionInput;
import com.ademola.esm.ticket.workflow.WorkflowDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Root of the ticket hierarchy: the fields every ticket type shares.
 *
 * <p><b>JOINED inheritance:</b> shared columns live in {@code work_items}, and each subclass adds a
 * table keyed by the same id ({@code incidents}, {@code service_requests}). Queues, search and
 * reports query {@code work_items} alone; loading one ticket joins in its type's table.
 *
 * <p>Other aggregates (category, requester, team, assignee) are referenced <b>by id</b>, not as JPA
 * associations. That avoids lazy-loading surprises and N+1 queries, and keeps the {@code ticket}
 * module decoupled from the {@code user}/{@code team} entity classes.
 *
 * <p>{@code status} is stored as text here; each subclass exposes it as its own enum and owns its
 * lifecycle. The database CHECK constraint rejects a status that doesn't belong to the type.
 */
@Entity
@Table(name = "work_items")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "type", discriminatorType = DiscriminatorType.STRING, length = 20)
public abstract class WorkItem extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 20)
    private String reference;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 30)
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Impact impact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Urgency urgency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Priority priority;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "subcategory_id")
    private UUID subcategoryId;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private UUID requesterId;

    @Column(name = "assigned_team_id", nullable = false)
    private UUID assignedTeamId;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "first_responded_at")
    private Instant firstRespondedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected WorkItem() {}

    protected WorkItem(WorkItemDraft draft, String initialStatus) {
        this.reference = draft.reference();
        this.title = draft.title().trim();
        this.description = draft.description();
        this.status = initialStatus;
        this.impact = draft.impact();
        this.urgency = draft.urgency();
        this.priority = draft.priority();
        this.categoryId = draft.categoryId();
        this.subcategoryId = draft.subcategoryId();
        this.requesterId = draft.requesterId();
        this.assignedTeamId = draft.assignedTeamId();
    }

    public abstract WorkItemType getType();

    /** The lifecycle this ticket type follows. */
    public abstract WorkflowDefinition<?> workflow();

    /**
     * Changes status. This is the <b>only</b> way a ticket's status changes.
     *
     * <p>The entity enforces the domain rules itself: the move must exist in the type's workflow
     * and its requirements must be met, whoever the caller is (API, assignment rules, scheduler).
     * <em>Who</em> may make the move is an authorization concern checked by the calling service.
     *
     * @return the transition that was applied
     */
    public final Transition<?> transition(String targetStatus, TransitionInput input, Instant now) {
        Transition<?> transition = workflow().require(status, targetStatus);
        transition.checkRequirements(input, assigneeId != null);
        String previous = status;
        this.status = transition.to().name();
        onTransition(previous, this.status, input, now);
        return transition;
    }

    /** Type-specific side effects of a status change (timestamps, resolution data...). */
    protected abstract void onTransition(String from, String to, TransitionInput input, Instant now);

    /** "First response" is when support first starts working on the ticket; set once, never moved. */
    protected void recordFirstResponse(Instant now) {
        if (firstRespondedAt == null) {
            firstRespondedAt = now;
        }
    }

    protected void markResolved(Instant now) {
        resolvedAt = now;
    }

    protected void clearResolved() {
        resolvedAt = null;
    }

    /** Set when the ticket reaches any terminal state (closed, cancelled, rejected). */
    protected void markClosed(Instant now) {
        closedAt = now;
    }

    /** Type-agnostic status name; subclasses expose a typed status. */
    public String getStatusName() {
        return status;
    }

    public String getReference() {
        return reference;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Impact getImpact() {
        return impact;
    }

    public Urgency getUrgency() {
        return urgency;
    }

    public Priority getPriority() {
        return priority;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getSubcategoryId() {
        return subcategoryId;
    }

    public UUID getRequesterId() {
        return requesterId;
    }

    public UUID getAssignedTeamId() {
        return assignedTeamId;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public Instant getFirstRespondedAt() {
        return firstRespondedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
