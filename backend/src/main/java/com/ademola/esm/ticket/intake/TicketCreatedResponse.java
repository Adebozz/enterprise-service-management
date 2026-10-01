package com.ademola.esm.ticket.intake;

import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import java.util.UUID;

/** Returned with 201 Created; the full ticket is at the {@code Location} header (/api/tickets/{id}). */
public record TicketCreatedResponse(UUID id, String reference, WorkItemType type, String status, Priority priority) {

    public static TicketCreatedResponse from(WorkItem item) {
        return new TicketCreatedResponse(
                item.getId(), item.getReference(), item.getType(), item.getStatusName(), item.getPriority());
    }
}
