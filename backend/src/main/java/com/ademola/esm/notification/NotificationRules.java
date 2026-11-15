package com.ademola.esm.notification;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Decides who hears about an audited change, and what they are told. Pure: no I/O, so each rule is
 * unit-tested on its own. The listener supplies the ticket and a way to look up a team's leads.
 *
 * <ul>
 *   <li>Assigned: the new assignee.
 *   <li>Status changed: the requester and the assignee.
 *   <li>Public reply or file: the requester and the assignee. Internal note or file: the assignee.
 *   <li>SLA breached: the assignee, or the team's leads while nobody owns the ticket.
 *   <li>Approval requested: the team's leads, except whoever raised the ticket.
 * </ul>
 *
 * Nobody is told about their own action, and one change tells each person once: a reply that
 * explains a status change, and a status change caused by the requester's reply, are covered by the
 * other notification.
 */
final class NotificationRules {

    private NotificationRules() {}

    static List<NotificationDraft> decide(
            AuditRecord record, UUID actorId, String actorName, TicketFacts ticket, Function<UUID, Set<UUID>> leadsOf) {
        String who = actorName == null ? "Someone" : actorName;
        String ref = ticket.reference();
        String title = ": " + ticket.title();
        return switch (record.action()) {
            case TICKET_ASSIGNMENT_CHANGED -> {
                Object newAssignee = value(record.newValue(), "assigneeId");
                Object oldAssignee = value(record.oldValue(), "assigneeId");
                if (!(newAssignee instanceof UUID assignee) || assignee.equals(oldAssignee)) {
                    yield List.of();
                }
                yield drafts(
                        recipients(actorId, assignee),
                        NotificationKind.ASSIGNED,
                        "%s assigned you %s%s".formatted(who, ref, title));
            }
            case TICKET_STATUS_CHANGED -> {
                if ("REQUESTER_REPLY".equals(text(record.metadata(), "trigger"))) {
                    yield List.of(); // the reply itself is the news
                }
                String status = statusLabel(text(record.newValue(), "status"));
                String message = actorId == null
                        ? "%s is now %s%s".formatted(ref, status, title)
                        : "%s moved %s to %s%s".formatted(who, ref, status, title);
                yield drafts(
                        recipients(actorId, ticket.requesterId(), ticket.assigneeId()),
                        NotificationKind.STATUS_CHANGED,
                        message);
            }
            case COMMENT_ADDED -> {
                if (record.metadata() != null && record.metadata().containsKey("relatedStatus")) {
                    yield List.of(); // the reason for a status change: that notification covers it
                }
                boolean internal = isInternal(record);
                yield drafts(
                        conversationRecipients(actorId, ticket, internal),
                        NotificationKind.COMMENTED,
                        internal
                                ? "%s added an internal note on %s%s".formatted(who, ref, title)
                                : "%s replied on %s%s".formatted(who, ref, title));
            }
            case ATTACHMENT_ADDED -> {
                String file = text(record.metadata(), "fileName");
                yield drafts(
                        conversationRecipients(actorId, ticket, isInternal(record)),
                        NotificationKind.FILE_ADDED,
                        "%s attached %s to %s%s".formatted(who, file, ref, title));
            }
            case SLA_BREACHED -> {
                Set<UUID> to = ticket.assigneeId() != null
                        ? recipients(actorId, ticket.assigneeId())
                        : without(leadsOf.apply(ticket.teamId()), actorId);
                String metric =
                        String.valueOf(text(record.metadata(), "metric")).toLowerCase(Locale.ROOT);
                yield drafts(
                        to, NotificationKind.SLA_BREACHED, "%s missed its %s target%s".formatted(ref, metric, title));
            }
            case APPROVAL_REQUESTED -> {
                Set<UUID> to = without(without(leadsOf.apply(ticket.teamId()), actorId), ticket.requesterId());
                yield drafts(
                        to,
                        NotificationKind.APPROVAL_REQUESTED,
                        "%s is waiting for your approval%s".formatted(ref, title));
            }
            default -> List.of();
        };
    }

    /** The audit entry's ticket: the entry's own entity, or for an approval the ticket it is for. */
    static UUID ticketIdOf(AuditRecord record) {
        if (record.action() == AuditAction.APPROVAL_REQUESTED) {
            return value(record.newValue(), "workItemId") instanceof UUID id ? id : null;
        }
        return switch (record.entityType()) {
            case WORK_ITEM -> record.entityId();
            default -> null;
        };
    }

    /** "WAITING_FOR_USER" → "waiting for user". The web app has nicer labels; emails make do. */
    static String statusLabel(String status) {
        return status == null ? "a new status" : status.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static Set<UUID> conversationRecipients(UUID actorId, TicketFacts ticket, boolean internal) {
        return internal
                ? recipients(actorId, ticket.assigneeId())
                : recipients(actorId, ticket.requesterId(), ticket.assigneeId());
    }

    private static boolean isInternal(AuditRecord record) {
        return "INTERNAL".equals(text(record.metadata(), "visibility"));
    }

    private static Set<UUID> recipients(UUID actorId, UUID... candidates) {
        Set<UUID> result = new LinkedHashSet<>();
        for (UUID candidate : candidates) {
            if (candidate != null && !candidate.equals(actorId)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static Set<UUID> without(Set<UUID> ids, UUID excluded) {
        Set<UUID> result = new LinkedHashSet<>(ids);
        result.remove(excluded);
        return result;
    }

    private static List<NotificationDraft> drafts(Set<UUID> to, NotificationKind kind, String message) {
        List<NotificationDraft> result = new ArrayList<>();
        to.forEach(recipient -> result.add(new NotificationDraft(recipient, kind, message)));
        return result;
    }

    private static Object value(Map<String, ?> values, String key) {
        return values == null ? null : values.get(key);
    }

    private static String text(Map<String, ?> values, String key) {
        Object value = value(values, key);
        return value == null ? null : value.toString();
    }
}
