package com.ademola.esm.notification;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.ResourceNotFoundException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Each person's own notifications. Nobody can see or change anyone else's. */
@Service
@Transactional
public class NotificationService {

    /** The bell shows the latest few; older ones are still counted as unread until read. */
    static final int INBOX_SIZE = 50;

    private final NotificationRepository notifications;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public NotificationInbox inbox(CurrentUser user) {
        return new NotificationInbox(
                notifications.countByRecipientIdAndReadAtIsNull(user.id()),
                notifications.findByRecipientIdOrderByCreatedAtDescIdDesc(user.id(), Limit.of(INBOX_SIZE)).stream()
                        .map(NotificationResponse::of)
                        .toList());
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public UnreadCount unreadCount(CurrentUser user) {
        return new UnreadCount(notifications.countByRecipientIdAndReadAtIsNull(user.id()));
    }

    @PreAuthorize("isAuthenticated()")
    public void markRead(CurrentUser user, UUID notificationId) {
        notifications
                .findById(notificationId)
                .filter(notification -> notification.getRecipientId().equals(user.id()))
                .orElseThrow(() -> new ResourceNotFoundException("Notification", notificationId))
                .markRead(clock.instant());
    }

    @PreAuthorize("isAuthenticated()")
    public void markAllRead(CurrentUser user) {
        notifications.markAllRead(user.id(), clock.instant());
    }
}
