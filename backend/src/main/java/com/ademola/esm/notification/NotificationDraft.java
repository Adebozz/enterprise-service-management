package com.ademola.esm.notification;

import java.util.UUID;

/** Who should be told what, decided by {@link NotificationRules} before anything is saved. */
record NotificationDraft(UUID recipientId, NotificationKind kind, String message) {}
