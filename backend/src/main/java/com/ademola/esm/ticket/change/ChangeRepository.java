package com.ademola.esm.ticket.change;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChangeRepository extends JpaRepository<Change, UUID> {

    /** The statuses in which a change holds a place on the schedule. */
    List<String> SCHEDULED = List.of(
            ChangeStatus.APPROVAL_PENDING.name(), ChangeStatus.APPROVED.name(), ChangeStatus.IMPLEMENTING.name());

    List<Change> findByProblemIdOrderByCreatedAtAsc(UUID problemId);

    /** Other changes in the given statuses whose planned window overlaps [start, end). */
    @Query("""
            select c from Change c
            where c.id <> :excluded and c.status in :statuses
              and c.plannedStart < :end and c.plannedEnd > :start
            order by c.plannedStart""")
    List<Change> findOverlapping(
            @Param("excluded") UUID excluded,
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("statuses") Collection<String> statuses);

    /** The schedule: changes in the given statuses whose window hasn't ended, soonest first. */
    @Query("""
            select c from Change c
            where c.status in :statuses and c.plannedEnd > :after
            order by c.plannedStart, c.reference""")
    List<Change> findScheduledEndingAfter(
            @Param("after") Instant after, @Param("statuses") Collection<String> statuses);
}
