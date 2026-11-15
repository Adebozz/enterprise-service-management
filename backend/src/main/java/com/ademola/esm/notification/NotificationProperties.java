package com.ademola.esm.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code esm.notifications}.
 *
 * @param appUrl where the web app is, for the link in each email
 */
@ConfigurationProperties("esm.notifications")
record NotificationProperties(
        @DefaultValue("http://localhost:5173") String appUrl,
        @DefaultValue Email email) {

    /**
     * @param enabled whether notifications are emailed as well as shown in the app; needs
     *     {@code spring.mail.host}
     * @param interval how often the outbox is checked
     */
    record Email(
            @DefaultValue("false") boolean enabled,

            @DefaultValue("Service Desk <no-reply@esm.local>")
            String from,

            @DefaultValue("30s") Duration interval) {}
}
