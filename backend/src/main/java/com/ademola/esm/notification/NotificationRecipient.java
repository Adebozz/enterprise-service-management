package com.ademola.esm.notification;

import java.util.UUID;

/** Where an email goes. */
record NotificationRecipient(UUID id, String email, String displayName, boolean active) {}
