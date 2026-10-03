package com.ademola.esm.team;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

public record TeamResponse(
        UUID id,
        String name,
        @Nullable String description,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    static TeamResponse from(Team team) {
        return new TeamResponse(
                team.getId(),
                team.getName(),
                team.getDescription(),
                team.isActive(),
                team.getCreatedAt(),
                team.getUpdatedAt(),
                team.getVersion());
    }
}
