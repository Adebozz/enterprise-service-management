package com.ademola.esm.ticket.query;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemRepository;
import com.ademola.esm.ticket.access.TicketAccessPolicy;
import com.ademola.esm.ticket.category.CategoryService;
import com.ademola.esm.ticket.incident.Incident;
import com.ademola.esm.ticket.request.ServiceRequest;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TicketQueryService {

    private final WorkItemRepository workItems;
    private final TicketAccessPolicy accessPolicy;
    private final CategoryService categories;
    private final UserService users;
    private final TeamService teams;

    public TicketQueryService(
            WorkItemRepository workItems,
            TicketAccessPolicy accessPolicy,
            CategoryService categories,
            UserService users,
            TeamService teams) {
        this.workItems = workItems;
        this.accessPolicy = accessPolicy;
        this.categories = categories;
        this.users = users;
        this.teams = teams;
    }

    /**
     * A ticket the caller may not see is reported as <b>not found</b>, exactly like one that
     * doesn't exist. A 403 would confirm to an outsider that the id is real.
     */
    @PreAuthorize("isAuthenticated()")
    public TicketResponse get(CurrentUser user, UUID id) {
        WorkItem item = workItems
                .findById(id)
                .filter(found -> accessPolicy.canView(user, found))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", id));
        return toResponse(item);
    }

    private TicketResponse toResponse(WorkItem item) {
        IncidentDetails incident = item instanceof Incident i
                ? new IncidentDetails(
                        i.getAffectedService(), i.getResolutionCode(), i.getResolutionNotes(), i.getReopenCount())
                : null;
        ServiceRequestDetails serviceRequest = item instanceof ServiceRequest r
                ? new ServiceRequestDetails(r.getCatalogueItemId(), r.getFulfilmentNotes())
                : null;
        return new TicketResponse(
                item.getId(),
                item.getReference(),
                item.getType(),
                item.getTitle(),
                item.getDescription(),
                item.getStatusName(),
                item.getImpact(),
                item.getUrgency(),
                item.getPriority(),
                new NamedRef(item.getCategoryId(), categories.nameOf(item.getCategoryId())),
                item.getSubcategoryId() == null
                        ? null
                        : new NamedRef(item.getSubcategoryId(), categories.nameOf(item.getSubcategoryId())),
                person(item.getRequesterId()),
                new NamedRef(
                        item.getAssignedTeamId(),
                        teams.summaryOf(item.getAssignedTeamId()).name()),
                item.getAssigneeId() == null ? null : person(item.getAssigneeId()),
                item.getCreatedAt(),
                item.getUpdatedAt(),
                item.getFirstRespondedAt(),
                item.getResolvedAt(),
                item.getClosedAt(),
                item.getVersion(),
                incident,
                serviceRequest);
    }

    private NamedRef person(UUID userId) {
        User user = users.require(userId);
        return new NamedRef(user.getId(), user.getDisplayName());
    }
}
