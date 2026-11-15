package com.ademola.esm.notification;

import com.ademola.esm.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
class NotificationController {

    private final NotificationService notificationService;

    NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(
            operationId = "listNotifications",
            summary = "Your latest 50 notifications, newest first, and your unread count")
    NotificationInbox inbox(@AuthenticationPrincipal CurrentUser user) {
        return notificationService.inbox(user);
    }

    @GetMapping("/unread-count")
    @Operation(operationId = "countUnreadNotifications", summary = "How many of your notifications are unread")
    UnreadCount unreadCount(@AuthenticationPrincipal CurrentUser user) {
        return notificationService.unreadCount(user);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "markNotificationRead", summary = "Mark one of your notifications as read")
    void markRead(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        notificationService.markRead(user, id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "markAllNotificationsRead", summary = "Mark all your notifications as read")
    void markAllRead(@AuthenticationPrincipal CurrentUser user) {
        notificationService.markAllRead(user);
    }
}
