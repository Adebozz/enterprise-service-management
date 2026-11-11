package com.ademola.esm.sla;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SLA settings under {@code esm.sla}. The breach-check schedule is read by {@link SlaBreachJob}.
 *
 * @param atRiskThreshold share of a target after which a running clock is at risk; also read by the
 *     queue, so it is validated here once at startup
 */
@ConfigurationProperties("esm.sla")
record SlaProperties(double atRiskThreshold) {

    SlaProperties {
        if (atRiskThreshold <= 0 || atRiskThreshold > 1) {
            throw new IllegalArgumentException("esm.sla.at-risk-threshold must be in (0, 1], was " + atRiskThreshold);
        }
    }
}
