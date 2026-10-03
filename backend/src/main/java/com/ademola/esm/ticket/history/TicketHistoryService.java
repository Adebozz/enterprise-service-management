package com.ademola.esm.ticket.history;

import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditEntry;
import com.ademola.esm.audit.AuditQueryService;
import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemRepository;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.query.NamedRef;
import com.ademola.esm.user.UserService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A ticket's audit timeline for support staff. Requesters follow their ticket through its status
 * and public comments instead; the full trail includes internal-note events and assignment detail.
 */
@Service
@Transactional(readOnly = true)
public class TicketHistoryService {

    private final WorkItemRepository workItems;
    private final TicketAccessPolicy accessPolicy;
    private final AuditQueryService auditQueries;
    private final UserService users;

    public TicketHistoryService(
            WorkItemRepository workItems,
            TicketAccessPolicy accessPolicy,
            AuditQueryService auditQueries,
            UserService users) {
        this.workItems = workItems;
        this.accessPolicy = accessPolicy;
        this.auditQueries = auditQueries;
        this.users = users;
    }

    @PreAuthorize("isAuthenticated()")
    public List<HistoryEntryResponse> history(CurrentUser user, UUID ticketId) {
        Set<UUID> teams = accessPolicy.teamScopeOf(user);
        WorkItem item = workItems
                .findById(ticketId)
                .filter(found -> TicketAccessPolicy.canView(user, found, teams))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
        if (!TicketAccessPolicy.canSeeStaffDetails(user, item, teams)) {
            throw new BusinessRuleException(
                    ErrorCode.ACCESS_DENIED, "Ticket history is available to the support staff handling it");
        }

        List<AuditEntry> entries = auditQueries.timeline(AuditEntityType.WORK_ITEM, ticketId);
        // All actor names in one query, not one per entry.
        Map<UUID, String> names = users.displayNamesOf(entries.stream()
                .map(AuditEntry::actorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return entries.stream()
                .map(entry -> new HistoryEntryResponse(
                        entry.occurredAt(),
                        entry.actorId() == null ? null : new NamedRef(entry.actorId(), names.get(entry.actorId())),
                        entry.action(),
                        entry.oldValue(),
                        entry.newValue(),
                        entry.metadata()))
                .toList();
    }
}
