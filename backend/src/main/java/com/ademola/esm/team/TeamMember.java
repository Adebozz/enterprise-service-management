package com.ademola.esm.team;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Membership of a user in a team.
 *
 * <p>Modelled as its own entity rather than a JPA {@code @ManyToMany} because the join row has data
 * ({@code joined_at}) and will be the target of a composite foreign key from tickets. It holds the
 * user's <em>id</em> rather than a {@code User} association: modules reference each other's
 * aggregates by id, which keeps the {@code team} module loosely coupled to {@code user}.
 */
@Entity
@Table(name = "team_members")
public class TeamMember {

    @EmbeddedId
    private TeamMemberId id;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected TeamMember() {}

    TeamMember(UUID teamId, UUID userId, Instant joinedAt) {
        this.id = new TeamMemberId(teamId, userId);
        this.joinedAt = joinedAt;
    }

    public TeamMemberId getId() {
        return id;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
