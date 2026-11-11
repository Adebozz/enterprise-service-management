package com.ademola.esm.sla;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Checks for breached SLAs every {@code esm.sla.breach-check.interval} (default one minute).
 *
 * <p>Batches keep each transaction short; the loop ends when a batch comes back partly empty.
 * Integration tests switch the job off and call {@link SlaBreachService} with a controlled clock.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "esm.sla.breach-check.enabled", havingValue = "true", matchIfMissing = true)
class SlaBreachJob {

    private final SlaBreachService breaches;
    private final Clock clock;

    SlaBreachJob(SlaBreachService breaches, Clock clock) {
        this.breaches = breaches;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${esm.sla.breach-check.interval:PT1M}",
            initialDelayString = "${esm.sla.breach-check.interval:PT1M}")
    void checkForBreaches() {
        Instant now = clock.instant();
        while (breaches.recordOverdueBatch(now) == SlaBreachService.BATCH_SIZE) {
            // keep going until a batch isn't full
        }
    }
}
