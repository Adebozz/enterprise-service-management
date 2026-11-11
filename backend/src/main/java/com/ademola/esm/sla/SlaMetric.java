package com.ademola.esm.sla;

/** What an SLA clock measures. */
enum SlaMetric {
    /** From creation until support first starts work. */
    RESPONSE,
    /** From creation until the ticket is resolved or fulfilled. */
    RESOLUTION
}
