package com.ademola.esm.ticket.query;

import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.priority.Urgency;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

/**
 * Full ticket view. Exactly one of {@code incident} / {@code serviceRequest} is set, matching
 * {@code type}; the common fields are the same for every type.
 */
public record TicketResponse(
        UUID id,
        String reference,
        WorkItemType type,
        String title,
        String description,
        String status,
        Impact impact,
        Urgency urgency,
        Priority priority,
        NamedRef category,
        @Nullable NamedRef subcategory,
        NamedRef requester,
        NamedRef assignedTeam,
        @Nullable NamedRef assignee,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant firstRespondedAt,
        @Nullable Instant resolvedAt,
        @Nullable Instant closedAt,
        long version,
        @Nullable IncidentDetails incident,
        @Nullable ServiceRequestDetails serviceRequest) {}
