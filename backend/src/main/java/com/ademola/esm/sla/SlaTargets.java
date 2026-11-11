package com.ademola.esm.sla;

import java.time.Duration;

/** How long support has to first respond to, and to resolve, a ticket. */
record SlaTargets(Duration response, Duration resolution) {

    Duration forMetric(SlaMetric metric) {
        return switch (metric) {
            case RESPONSE -> response;
            case RESOLUTION -> resolution;
        };
    }
}
