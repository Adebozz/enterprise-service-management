package com.ademola.esm.team;

import com.ademola.esm.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A support team (e.g. "Network Team") that owns a queue of tickets.
 *
 * <p>Membership is deliberately <em>not</em> a collection on this entity. Members are queried
 * through {@link TeamMemberRepository} when needed, so loading a team never loads all of its
 * members, and adding a member never requires loading the others.
 */
@Entity
@Table(name = "teams")
public class Team extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    protected Team() {}

    Team(String name, String description) {
        this.name = name.trim();
        this.description = description;
    }

    void rename(String newName) {
        this.name = newName.trim();
    }

    void describe(String newDescription) {
        this.description = newDescription;
    }

    void activate() {
        this.active = true;
    }

    void deactivate() {
        this.active = false;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }
}
