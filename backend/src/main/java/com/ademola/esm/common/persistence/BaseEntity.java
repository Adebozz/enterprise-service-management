package com.ademola.esm.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Identity, optimistic-locking version and timestamps shared by our mutable aggregates.
 *
 * <p>IDs are UUIDv7, which are time-ordered. Unlike random UUIDv4 they are inserted at the "end"
 * of the primary-key B-tree, which keeps index pages dense and inserts cheap.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Incremented by Hibernate on every update; the UPDATE fails if the row changed underneath us. */
    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp(source = SourceType.VM)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.VM)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // Identity-based equality that is stable before and after persist: an unsaved entity equals only
    // itself, and the hash code never changes when Hibernate assigns the id.
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || org.hibernate.Hibernate.getClass(this) != org.hibernate.Hibernate.getClass(other)) {
            return false;
        }
        return id != null && id.equals(((BaseEntity) other).id);
    }

    @Override
    public final int hashCode() {
        return org.hibernate.Hibernate.getClass(this).hashCode();
    }
}
