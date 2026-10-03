package com.ademola.esm.ticket;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Polymorphic: {@code findById} returns the concrete subclass (Incident, ServiceRequest...). */
public interface WorkItemRepository extends JpaRepository<WorkItem, UUID> {

    /**
     * Sets the first-response time if it isn't set yet. Atomic (two simultaneous first comments can't
     * both "win") and deliberately bypasses {@code @Version}: the field is write-once, so it can't
     * conflict with anything, and bumping the version would give other users a spurious 409.
     *
     * @return 1 if this call recorded the first response, 0 if it was already recorded
     */
    @Modifying(flushAutomatically = true)
    @Query("update WorkItem w set w.firstRespondedAt = :at where w.id = :id and w.firstRespondedAt is null")
    int recordFirstResponseIfAbsent(UUID id, Instant at);
}
