package com.ademola.esm.notification;

import com.ademola.esm.ticket.WorkItem;
import java.util.UUID;

/** What the rules need to know about the ticket an audit entry is about. */
record TicketFacts(UUID id, String reference, String title, UUID requesterId, UUID assigneeId, UUID teamId) {

    static TicketFacts of(WorkItem item) {
        return new TicketFacts(
                item.getId(),
                item.getReference(),
                item.getTitle(),
                item.getRequesterId(),
                item.getAssigneeId(),
                item.getAssignedTeamId());
    }
}
