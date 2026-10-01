package com.ademola.esm.ticket.query;

import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.priority.Urgency;
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
        NamedRef subcategory,
        NamedRef requester,
        NamedRef assignedTeam,
        NamedRef assignee,
        Instant createdAt,
        Instant updatedAt,
        Instant firstRespondedAt,
        Instant resolvedAt,
        Instant closedAt,
        long version,
        IncidentDetails incident,
        ServiceRequestDetails serviceRequest) {}
