package com.ademola.esm.sla;

/** What a clock means to people at a given moment. Derived, never stored. */
enum SlaStatus {
    ON_TRACK,
    /** Running, with most of the target used up. */
    AT_RISK,
    /** Past its target and the work isn't done. */
    BREACHED,
    PAUSED,
    /** Done within the target. */
    MET,
    /** Done, but after the target. */
    MISSED,
    CANCELLED
}
