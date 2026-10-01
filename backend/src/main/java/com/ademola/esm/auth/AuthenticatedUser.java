package com.ademola.esm.auth;

import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import java.util.UUID;

/** Minimal user info the client needs right after login/refresh to render the right portal. */
public record AuthenticatedUser(UUID id, String email, String displayName, Role role) {

    static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }
}
