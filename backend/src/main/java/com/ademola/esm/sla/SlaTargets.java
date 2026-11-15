package com.ademola.esm.sla;

import java.time.Duration;

/**
 * How long support has to first respond to, and to resolve, a ticket.
 *
 * @param businessHours whether the targets count only the desk's working hours
 */
record SlaTargets(Duration response, Duration resolution, boolean businessHours) {

    Duration forMetric(SlaMetric metric) {
        return switch (metric) {
            case RESPONSE -> response;
            case RESOLUTION -> resolution;
        };
    }
}
