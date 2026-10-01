package com.ademola.esm.team;

import com.ademola.esm.user.Role;
import java.time.Instant;
import java.util.UUID;

public record TeamMemberResponse(
        UUID userId, String displayName, String email, Role role, boolean active, Instant joinedAt) {}
