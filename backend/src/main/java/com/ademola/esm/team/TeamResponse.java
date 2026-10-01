package com.ademola.esm.team;

import java.time.Instant;
import java.util.UUID;

public record TeamResponse(
        UUID id, String name, String description, boolean active, Instant createdAt, Instant updatedAt, long version) {

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
