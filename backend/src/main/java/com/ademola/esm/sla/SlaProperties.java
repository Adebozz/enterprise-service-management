package com.ademola.esm.sla;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SLA settings under {@code esm.sla}. The breach-check schedule is read by {@link SlaBreachJob}.
 *
 * @param atRiskThreshold share of a target after which a running clock is at risk; also read by the
 *     queue, so it is validated here once at startup
 * @param businessHours where the business calendar's hours are
 */
@ConfigurationProperties("esm.sla")
record SlaProperties(double atRiskThreshold, @DefaultValue BusinessHours businessHours) {

    SlaProperties {
        if (atRiskThreshold <= 0 || atRiskThreshold > 1) {
            throw new IllegalArgumentException("esm.sla.at-risk-threshold must be in (0, 1], was " + atRiskThreshold);
        }
    }

    /** @param zone the time zone of the opening hours, e.g. Europe/London; checked at startup */
    record BusinessHours(@DefaultValue("Europe/London") String zone) {

        BusinessHours {
            ZoneId.of(zone);
        }

        ZoneId zoneId() {
            return ZoneId.of(zone);
        }
    }
}
