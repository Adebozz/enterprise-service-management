package com.ademola.esm.team;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

/** Composite primary key of {@code team_members}. Records give us correct equals/hashCode for free. */
@Embeddable
public record TeamMemberId(
        @Column(name = "team_id", nullable = false) UUID teamId,
        @Column(name = "user_id", nullable = false) UUID userId) {}
