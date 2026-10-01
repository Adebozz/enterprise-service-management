package com.ademola.esm.auth;

import com.ademola.esm.audit.ActorProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The audit actor is the authenticated user of the current request; empty for system actions. */
@Component
class SecurityContextActorProvider implements ActorProvider {

    @Override
    public Optional<UUID> currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return Optional.of(user.id());
        }
        return Optional.empty();
    }
}
