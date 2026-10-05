package com.ademola.esm.ticket.history;

import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditEntry;
import com.ademola.esm.audit.AuditQueryService;
import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemRepository;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.category.CategoryService;
import com.ademola.esm.ticket.query.NamedRef;
import com.ademola.esm.user.UserService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
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
    private final TeamService teams;
    private final CategoryService categories;

    public TicketHistoryService(
            WorkItemRepository workItems,
            TicketAccessPolicy accessPolicy,
            AuditQueryService auditQueries,
            UserService users,
            TeamService teams,
            CategoryService categories) {
        this.workItems = workItems;
        this.accessPolicy = accessPolicy;
        this.auditQueries = auditQueries;
        this.users = users;
        this.teams = teams;
        this.categories = categories;
    }

    @PreAuthorize("isAuthenticated()")
    public List<HistoryEntryResponse> history(CurrentUser user, UUID ticketId) {
        Set<UUID> teamScope = accessPolicy.teamScopeOf(user);
        WorkItem item = workItems
                .findById(ticketId)
                .filter(found -> TicketAccessPolicy.canView(user, found, teamScope))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
        if (!TicketAccessPolicy.canSeeStaffDetails(user, item, teamScope)) {
            throw new BusinessRuleException(
                    ErrorCode.ACCESS_DENIED, "Ticket history is available to the support staff handling it");
        }

        List<AuditEntry> entries = auditQueries.timeline(AuditEntityType.WORK_ITEM, ticketId);

        // Resolve every id the timeline mentions (actors, assignees, teams, categories) with one
        // query per kind, not one per entry.
        Set<UUID> ids = new HashSet<>();
        entries.forEach(entry -> {
            if (entry.actorId() != null) {
                ids.add(entry.actorId());
            }
            ids.addAll(referencedIds(entry));
        });
        Map<UUID, String> names = new HashMap<>(users.displayNamesOf(ids));
        names.putAll(teams.namesOf(ids));
        names.putAll(categories.namesOf(ids));

        return entries.stream()
                .map(entry -> new HistoryEntryResponse(
                        entry.occurredAt(),
                        entry.actorId() == null ? null : new NamedRef(entry.actorId(), names.get(entry.actorId())),
                        entry.action(),
                        entry.oldValue(),
                        entry.newValue(),
                        entry.metadata(),
                        namesFor(referencedIds(entry), names)))
                .toList();
    }

    /** Ids in fields named "...Id" (assigneeId, teamId, categoryId...) in any of the entry's values. */
    static Set<UUID> referencedIds(AuditEntry entry) {
        Set<UUID> ids = new HashSet<>();
        Stream.of(entry.oldValue(), entry.newValue(), entry.metadata())
                .filter(Objects::nonNull)
                .flatMap(node -> node.properties().stream())
                .filter(field ->
                        field.getKey().endsWith("Id") && field.getValue().isString())
                .forEach(field -> {
                    try {
                        ids.add(UUID.fromString(field.getValue().asString()));
                    } catch (IllegalArgumentException notAnId) {
                        // not every "...Id" value has to be a UUID
                    }
                });
        return ids;
    }

    private static Map<String, String> namesFor(Set<UUID> ids, Map<UUID, String> names) {
        Map<String, String> result = new TreeMap<>();
        ids.stream().filter(names::containsKey).forEach(id -> result.put(id.toString(), names.get(id)));
        return result;
    }
}
