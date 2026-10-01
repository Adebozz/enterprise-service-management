package com.ademola.esm.ticket;

import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.ticket.priority.Urgency;
import java.util.UUID;

/**
 * Everything a new ticket needs that is common to all types, already validated and resolved
 * (reference allocated, priority calculated, team routed) by {@code TicketIntake}.
 */
public record WorkItemDraft(
        String reference,
        String title,
        String description,
        Impact impact,
        Urgency urgency,
        Priority priority,
        UUID categoryId,
        UUID subcategoryId,
        UUID requesterId,
        UUID assignedTeamId) {}
