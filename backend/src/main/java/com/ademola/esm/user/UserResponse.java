package com.ademola.esm.user;

import java.time.Instant;
import java.util.UUID;

/** API view of a user. Deliberately has no password hash field: it can't leak if it isn't here. */
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        Role role,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.isActive(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getVersion());
    }
}
