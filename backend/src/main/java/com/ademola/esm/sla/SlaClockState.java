package com.ademola.esm.sla;

/**
 * Whether a clock is counting. Whether it breached is recorded separately ({@code breached_at}),
 * because a breached clock keeps running until the work is done.
 */
enum SlaClockState {
    RUNNING,
    /** Not counting, e.g. while waiting for the requester. */
    PAUSED,
    /** The measured work is done. A reopened ticket's resolution clock runs again. */
    COMPLETED,
    /** The ticket was cancelled; the clock no longer counts towards SLA results. */
    CANCELLED
}
