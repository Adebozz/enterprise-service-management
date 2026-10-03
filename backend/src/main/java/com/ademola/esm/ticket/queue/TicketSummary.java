package com.ademola.esm.ticket.queue;

import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.query.NamedRef;
import java.time.Instant;
import java.util.UUID;

/** One row of a queue or search result, with names resolved in the same query. */
public record TicketSummary(
        UUID id,
        String reference,
        WorkItemType type,
        String title,
        String status,
        Priority priority,
        NamedRef category,
        NamedRef assignedTeam,
        NamedRef assignee,
        NamedRef requester,
        Instant createdAt,
        Instant updatedAt,
        Instant firstRespondedAt,
        Instant resolvedAt) {}
