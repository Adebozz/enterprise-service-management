package com.ademola.esm.notification;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends the outbox: notifications waiting to be emailed. Each batch is one short transaction that
 * locks its rows, sends, and records the result; a failure is retried on the next run, up to
 * {@link #MAX_ATTEMPTS} times. Delivery is at least once: if the commit failed after a send, the
 * email would go again.
 */
@Service
public class NotificationEmailService {

    static final int BATCH_SIZE = 20;
    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    private final NotificationRepository notifications;
    private final MailGateway mail;
    private final NotificationProperties properties;
    private final Clock clock;

    NotificationEmailService(
            NotificationRepository notifications, MailGateway mail, NotificationProperties properties, Clock clock) {
        this.notifications = notifications;
        this.mail = mail;
        this.properties = properties;
        this.clock = clock;
    }

    /** Sends one batch; returns how many rows it handled, so the caller knows whether to go on. */
    @Transactional
    public int sendPendingBatch() {
        List<Notification> batch = notifications.lockPendingEmails(BATCH_SIZE);
        if (batch.isEmpty()) {
            return 0;
        }
        Map<java.util.UUID, NotificationRecipient> recipients =
                notifications
                        .findRecipients(
                                batch.stream().map(Notification::getRecipientId).toList())
                        .stream()
                        .collect(Collectors.toMap(NotificationRecipient::id, Function.identity()));
        for (Notification notification : batch) {
            NotificationRecipient to = recipients.get(notification.getRecipientId());
            if (to == null || !to.active()) {
                notification.emailSkipped(); // deactivated accounts get no mail
                continue;
            }
            try {
                mail.send(properties.email().from(), to.email(), subject(notification), body(notification, to));
                notification.emailSent(clock.instant());
            } catch (RuntimeException e) {
                notification.emailFailed(MAX_ATTEMPTS);
                log.warn(
                        "Email for notification {} failed (attempt {}): {}",
                        notification.getId(),
                        notification.getEmailAttempts(),
                        e.getMessage());
            }
        }
        return batch.size();
    }

    static String subject(Notification notification) {
        String message = notification.getMessage();
        return message.length() <= 120 ? message : message.substring(0, 119) + "…";
    }

    String body(Notification notification, NotificationRecipient to) {
        return """
                Hello %s,

                %s

                Open the ticket: %s/tickets/%s

                You get this email because you are involved in this ticket.
                """.formatted(
                        to.displayName(),
                        notification.getMessage(),
                        properties.appUrl().replaceAll("/+$", ""),
                        notification.getWorkItemId());
    }
}
