package com.ademola.esm.sla;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.ticket.TicketChangedEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps each ticket's SLA clocks in line with the ticket.
 *
 * <p>Rather than mapping each transition to a clock operation, every change <b>reconciles</b> the
 * clocks with the ticket's current state (its {@link SlaPhase} and whether support has responded).
 * Reconciling is idempotent, so a status reached by any route (API, assignment, auto-resume) gets
 * the same result, and a new transition needs no SLA code unless it introduces a new status.
 */
@Service
class SlaClockService {

    private static final Logger log = LoggerFactory.getLogger(SlaClockService.class);

    private final SlaClockRepository clocks;
    private final SlaPolicyService policies;
    private final AuditService audit;

    SlaClockService(SlaClockRepository clocks, SlaPolicyService policies, AuditService audit) {
        this.clocks = clocks;
        this.policies = policies;
        this.audit = audit;
    }

    /** Runs synchronously in the ticket change's transaction, so both commit or roll back together. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(TicketChangedEvent event) {
        if (event.created()) {
            SlaTargets targets = policies.targetsFor(event.type(), event.priority());
            for (SlaMetric metric : SlaMetric.values()) {
                clocks.save(SlaClock.start(event.ticketId(), metric, targets.forMetric(metric), event.occurredAt()));
            }
            return;
        }
        // Tickets raised before the SLA engine existed have no clocks; they stay without an SLA rather
        // than getting a clock that pretends to know their history.
        List<SlaClock> ticketClocks = clocks.findByWorkItemId(event.ticketId());
        SlaPhase phase = SlaPhase.of(event.type(), event.status());
        for (SlaClock clock : ticketClocks) {
            Instant breachedBefore = clock.getBreachedAt();
            reconcile(clock, phase, event.responded(), event.occurredAt());
            if (breachedBefore == null && clock.getBreachedAt() != null) {
                recordBreach(clock);
            }
        }
    }

    /** Brings one clock in line with the ticket's state. Package-private for unit tests. */
    static void reconcile(SlaClock clock, SlaPhase phase, boolean responded, Instant now) {
        SlaClockState state = clock.getState();
        boolean open = state == SlaClockState.RUNNING || state == SlaClockState.PAUSED;
        if (phase == SlaPhase.CANCELLED) {
            if (open) {
                clock.cancel(now);
            }
            return;
        }
        boolean finished =
                switch (clock.getMetric()) {
                    case RESPONSE -> responded;
                    case RESOLUTION -> phase == SlaPhase.DONE;
                };
        if (finished) {
            if (open) {
                clock.complete(now);
            }
        } else if (phase == SlaPhase.WAITING) {
            if (state == SlaClockState.RUNNING) {
                clock.pause(now);
            }
        } else if (state == SlaClockState.PAUSED) {
            clock.resume(now);
        } else if (state == SlaClockState.COMPLETED && clock.getMetric() == SlaMetric.RESOLUTION) {
            clock.reopen(now); // a resolved ticket was reopened
        }
    }

    /** Called for every newly recorded breach, whether found here or by the breach job. */
    void recordBreach(SlaClock clock) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("metric", clock.getMetric());
        metadata.put("targetMinutes", clock.target().toMinutes());
        metadata.put("breachedAt", clock.getBreachedAt());
        audit.recordAsSystem(AuditRecord.event(
                AuditAction.SLA_BREACHED, AuditEntityType.WORK_ITEM, clock.getWorkItemId(), metadata));
        log.info(
                "SLA {} breached for ticket {} at {}", clock.getMetric(), clock.getWorkItemId(), clock.getBreachedAt());
    }
}
