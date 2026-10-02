package com.ademola.esm.ticket.assignment;

import com.ademola.esm.ticket.query.NamedRef;
import java.util.UUID;

/**
 * Deliberately not the full ticket: after transferring a ticket to another team, the caller may
 * no longer be allowed to see it.
 */
public record AssignmentResponse(
        UUID ticketId, String reference, String status, NamedRef assignedTeam, NamedRef assignee, long version) {}
