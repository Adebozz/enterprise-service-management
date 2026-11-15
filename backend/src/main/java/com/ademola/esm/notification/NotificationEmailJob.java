package com.ademola.esm.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Empties the email outbox every {@code esm.notifications.email.interval}. Only runs when email is
 * enabled; integration tests call {@link NotificationEmailService} directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "esm.notifications.email.enabled", havingValue = "true")
class NotificationEmailJob {

    private final NotificationEmailService emails;

    NotificationEmailJob(NotificationEmailService emails) {
        this.emails = emails;
    }

    @Scheduled(
            fixedDelayString = "${esm.notifications.email.interval:PT30S}",
            initialDelayString = "${esm.notifications.email.interval:PT30S}")
    void sendPending() {
        while (emails.sendPendingBatch() == NotificationEmailService.BATCH_SIZE) {
            // keep going until a batch isn't full
        }
    }
}
