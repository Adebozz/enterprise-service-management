package com.ademola.esm.sla;

import java.time.Duration;
import java.time.Instant;

/**
 * Which time counts towards an SLA target: every minute ({@link #ALWAYS}) or only the service
 * desk's working hours ({@link BusinessCalendar}).
 */
interface SlaSchedule {

    /** Counted time between two instants; zero if {@code to} isn't after {@code from}. */
    Duration workingTime(Instant from, Instant to);

    /**
     * The instant at which {@code amount} of counted time has passed since {@code start}. Around the
     * clock a negative amount gives an earlier instant; in business hours, {@code start} itself.
     */
    Instant addWorkingTime(Instant start, Duration amount);

    boolean isBusinessHours();

    /** 24/7: plain elapsed time. */
    SlaSchedule ALWAYS = new SlaSchedule() {
        @Override
        public Duration workingTime(Instant from, Instant to) {
            return to.isAfter(from) ? Duration.between(from, to) : Duration.ZERO;
        }

        @Override
        public Instant addWorkingTime(Instant start, Duration amount) {
            return start.plus(amount);
        }

        @Override
        public boolean isBusinessHours() {
            return false;
        }

        @Override
        public String toString() {
            return "ALWAYS";
        }
    };
}
