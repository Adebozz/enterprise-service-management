package com.ademola.esm.notification;

import com.ademola.esm.audit.AuditRecordedEvent;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.ticket.WorkItemRepository;
import com.ademola.esm.user.UserService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns audit entries into notifications. The audit trail already records every change worth
 * telling someone about, with who did it; listening to it means no feature has to remember to
 * notify, and nothing outside this module knows notifications exist.
 *
 * <p>Runs in the change's transaction: the notifications exist exactly when the change does.
 * Emailing happens later, from the outbox, so a slow or broken mail server never blocks a change.
 */
@Component
class NotificationListener {

    private final NotificationRepository notifications;
    private final WorkItemRepository workItems;
    private final UserService users;
    private final TeamService teams;
    private final NotificationProperties properties;

    NotificationListener(
            NotificationRepository notifications,
            WorkItemRepository workItems,
            UserService users,
            TeamService teams,
            NotificationProperties properties) {
        this.notifications = notifications;
        this.workItems = workItems;
        this.users = users;
        this.teams = teams;
        this.properties = properties;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(AuditRecordedEvent event) {
        UUID ticketId = NotificationRules.ticketIdOf(event.record());
        if (ticketId == null) {
            return;
        }
        workItems.findById(ticketId).ifPresent(item -> {
            UUID actorId = event.actorId();
            String actorName = actorId == null
                    ? null
                    : users.displayNamesOf(Set.of(actorId)).get(actorId);
            List<NotificationDraft> drafts = NotificationRules.decide(
                    event.record(), actorId, actorName, TicketFacts.of(item), teams::activeLeadIdsOf);
            drafts.forEach(draft -> notifications.save(new Notification(
                    draft,
                    ticketId,
                    actorId,
                    event.occurredAt(),
                    properties.email().enabled())));
        });
    }
}
