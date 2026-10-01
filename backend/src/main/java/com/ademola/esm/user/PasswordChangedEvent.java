package com.ademola.esm.user;

import java.util.UUID;

/**
 * Published (inside the transaction) when a user changes their password. The {@code auth} module
 * listens and revokes every refresh token of that user, signing out all other sessions, including
 * any an attacker may hold.
 */
public record PasswordChangedEvent(UUID userId) {}
