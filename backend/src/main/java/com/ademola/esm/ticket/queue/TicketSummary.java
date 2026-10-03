package com.ademola.esm.ticket.queue;

import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.query.NamedRef;
import jakarta.annotation.Nullable;
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
        @Nullable NamedRef assignee,
        NamedRef requester,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant firstRespondedAt,
        @Nullable Instant resolvedAt) {}
