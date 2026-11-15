package com.ademola.esm.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id, UUID ticketId, NotificationKind kind, String message, Instant createdAt, boolean read) {

    static NotificationResponse of(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getWorkItemId(),
                notification.getKind(),
                notification.getMessage(),
                notification.getCreatedAt(),
                notification.getReadAt() != null);
    }
}
