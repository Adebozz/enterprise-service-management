package com.ademola.esm.approval;

import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.query.NamedRef;
import java.time.Instant;
import java.util.UUID;

/** One entry in an approver's inbox: the approval and enough of its ticket (a request or a change) to decide. */
public record PendingApprovalResponse(
        UUID id,
        long version,
        Instant requestedAt,
        UUID ticketId,
        WorkItemType type,
        String reference,
        String title,
        String description,
        Priority priority,
        NamedRef requester,
        NamedRef team) {}
