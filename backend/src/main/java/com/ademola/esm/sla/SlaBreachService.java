package com.ademola.esm.sla;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds running clocks whose deadline has passed and records the breach.
 *
 * <p>Clocks also record a breach when they pause or stop late; this catches the ones nobody touches,
 * so "breached" shows up within one job interval even on a ticket no one is working on.
 */
@Service
class SlaBreachService {

    static final int BATCH_SIZE = 100;

    private final SlaClockRepository clocks;
    private final SlaClockService clockService;

    SlaBreachService(SlaClockRepository clocks, SlaClockService clockService) {
        this.clocks = clocks;
        this.clockService = clockService;
    }

    /**
     * Records breaches for one batch of overdue clocks, in one short transaction.
     *
     * @return how many breaches were recorded; fewer than {@link #BATCH_SIZE} means none are left
     */
    @Transactional
    int recordOverdueBatch(Instant now) {
        int recorded = 0;
        for (SlaClock clock : clocks.lockOverdue(now, BATCH_SIZE)) {
            if (clock.recordBreachIfOverdue(now)) {
                clockService.recordBreach(clock);
                recorded++;
            }
        }
        return recorded;
    }
}
