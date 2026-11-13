package com.ademola.esm.ticket.change;

/** How much scrutiny a change needs before it happens. */
public enum ChangeType {
    /** Routine and low risk, done the same way every time: pre-approved, so it is simply scheduled. */
    STANDARD,
    /** Needs approval, and must be planned for a window that has not started yet. */
    NORMAL,
    /** Needs approval, but may be carried out straight away: something is broken now. */
    EMERGENCY
}
