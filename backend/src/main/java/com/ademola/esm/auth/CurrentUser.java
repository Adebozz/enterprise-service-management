package com.ademola.esm.auth;

import com.ademola.esm.user.Role;
import java.util.UUID;

/**
 * The authenticated caller, reconstructed from the access token on every request. Controllers
 * receive it with {@code @AuthenticationPrincipal CurrentUser user}. Business code must take the
 * user's identity from here, never from request bodies or paths, which prevents "act as someone
 * else" bugs (IDOR).
 */
public record CurrentUser(UUID id, Role role, String displayName) {

    public boolean has(Role required) {
        return role.includes(required);
    }
}
