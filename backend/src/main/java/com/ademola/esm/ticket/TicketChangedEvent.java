package com.ademola.esm.ticket;

import com.ademola.esm.ticket.priority.Priority;
import java.time.Instant;
import java.util.UUID;

/**
 * Something that service levels measure changed: a ticket was created, changed status or got its
 * first response.
 *
 * <p>Published by the ticket services and handled by the {@code sla} module, so {@code ticket} never
 * depends on {@code sla}. Listeners are synchronous and run in the publisher's transaction (as
 * {@code UserRoleChangedEvent} does), so the ticket and its SLA clocks commit or roll back together.
 *
 * @param responded whether support has responded; carried explicitly because a comment records the
 *     first response with a direct update the loaded entity doesn't see
 */
public record TicketChangedEvent(
        UUID ticketId,
        WorkItemType type,
        Priority priority,
        String status,
        boolean responded,
        boolean created,
        Instant occurredAt) {

    public static TicketChangedEvent created(WorkItem item, Instant at) {
        return new TicketChangedEvent(
                item.getId(), item.getType(), item.getPriority(), item.getStatusName(), false, true, at);
    }

    public static TicketChangedEvent changed(WorkItem item, Instant at) {
        return new TicketChangedEvent(
                item.getId(),
                item.getType(),
                item.getPriority(),
                item.getStatusName(),
                item.getFirstRespondedAt() != null,
                false,
                at);
    }

    public static TicketChangedEvent firstResponse(WorkItem item, Instant at) {
        return new TicketChangedEvent(
                item.getId(), item.getType(), item.getPriority(), item.getStatusName(), true, false, at);
    }
}
