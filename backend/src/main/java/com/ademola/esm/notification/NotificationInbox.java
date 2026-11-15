package com.ademola.esm.notification;

import java.util.List;

/** The caller's latest notifications and how many of all of theirs are unread. */
public record NotificationInbox(long unreadCount, List<NotificationResponse> items) {}
