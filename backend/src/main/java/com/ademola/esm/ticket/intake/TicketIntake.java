package com.ademola.esm.ticket.intake;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.ticket.ReferenceNumberGenerator;
import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemDraft;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.category.CategoryChoice;
import com.ademola.esm.ticket.category.CategoryService;
import com.ademola.esm.ticket.priority.Impact;
import com.ademola.esm.ticket.priority.PriorityPolicy;
import com.ademola.esm.ticket.priority.Urgency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The steps every new ticket goes through, whatever its type: validate the category, route to a
 * team, calculate priority, allocate a reference, and (after saving) audit the creation.
 *
 * <p>Composition rather than an abstract base service: incident and service-request services
 * each call this, so neither needs to inherit from the other.
 */
@Component
public class TicketIntake {

    private final CategoryService categories;
    private final TeamService teams;
    private final PriorityPolicy priorityPolicy;
    private final ReferenceNumberGenerator references;
    private final AuditService audit;

    public TicketIntake(
            CategoryService categories,
            TeamService teams,
            PriorityPolicy priorityPolicy,
            ReferenceNumberGenerator references,
            AuditService audit) {
        this.categories = categories;
        this.teams = teams;
        this.priorityPolicy = priorityPolicy;
        this.references = references;
        this.audit = audit;
    }

    public record NewTicket(
            WorkItemType type,
            String title,
            String description,
            UUID categoryId,
            UUID subcategoryId,
            Impact impact,
            Urgency urgency) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public WorkItemDraft prepare(CurrentUser requester, NewTicket ticket) {
        CategoryChoice choice =
                categories.chooseForNewTicket(ticket.categoryId(), ticket.subcategoryId(), ticket.type());
        teams.requireActiveTeam(choice.routedTeamId()); // a deactivated team must not silently receive work
        return new WorkItemDraft(
                references.next(ticket.type()),
                ticket.title(),
                ticket.description(),
                ticket.impact(),
                ticket.urgency(),
                priorityPolicy.calculate(ticket.impact(), ticket.urgency()),
                choice.categoryId(),
                choice.subcategoryId(),
                requester.id(),
                choice.routedTeamId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreated(WorkItem item) {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("reference", item.getReference());
        created.put("type", item.getType());
        created.put("status", item.getStatusName());
        created.put("impact", item.getImpact());
        created.put("urgency", item.getUrgency());
        created.put("priority", item.getPriority());
        created.put("categoryId", item.getCategoryId());
        created.put("subcategoryId", item.getSubcategoryId());
        created.put("assignedTeamId", item.getAssignedTeamId());
        audit.record(AuditRecord.created(AuditAction.TICKET_CREATED, AuditEntityType.WORK_ITEM, item.getId(), created));
    }
}
