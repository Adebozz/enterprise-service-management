package com.ademola.esm.ticket.category;

import com.ademola.esm.common.persistence.BaseEntity;
import com.ademola.esm.ticket.WorkItemType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Ticket classification, two levels deep (e.g. NETWORK, then WIFI). Doubles as the routing
 * table: a ticket goes to the subcategory's team if it has one, otherwise to the parent's.
 */
@Entity
@Table(name = "categories")
public class Category extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Column(name = "default_team_id")
    private UUID defaultTeamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to", nullable = false, length = 20)
    private CategoryScope appliesTo;

    @Column(nullable = false)
    private boolean active = true;

    protected Category() {}

    Category(String code, String name, UUID parentId, UUID defaultTeamId, CategoryScope appliesTo) {
        this.code = code;
        this.name = name.trim();
        this.parentId = parentId;
        this.defaultTeamId = defaultTeamId;
        this.appliesTo = appliesTo;
    }

    public boolean isTopLevel() {
        return parentId == null;
    }

    public boolean appliesTo(WorkItemType type) {
        return appliesTo.includes(type);
    }

    void rename(String newName) {
        this.name = newName.trim();
    }

    void routeTo(UUID teamId) {
        this.defaultTeamId = teamId;
    }

    void activate() {
        this.active = true;
    }

    void deactivate() {
        this.active = false;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public UUID getParentId() {
        return parentId;
    }

    public UUID getDefaultTeamId() {
        return defaultTeamId;
    }

    public CategoryScope getAppliesTo() {
        return appliesTo;
    }

    public boolean isActive() {
        return active;
    }
}
