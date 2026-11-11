package com.ademola.esm.sla;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SlaClockRepository extends JpaRepository<SlaClock, UUID> {

    Optional<SlaClock> findByWorkItemIdAndMetric(UUID workItemId, SlaMetric metric);

    List<SlaClock> findByWorkItemId(UUID workItemId);

    /**
     * Running, unbreached clocks whose deadline has passed, locked for this transaction.
     *
     * <p>{@code SKIP LOCKED}: if two app instances run the job at once, each claims different rows
     * instead of waiting for the other or recording the same breach twice. Served by the partial
     * index {@code sla_clocks_breach_due_idx}.
     */
    @Query(
            value = "select * from sla_clocks where state = 'RUNNING' and breached_at is null and due_at <= :now"
                    + " order by due_at limit :limit for update skip locked",
            nativeQuery = true)
    List<SlaClock> lockOverdue(Instant now, int limit);
}
