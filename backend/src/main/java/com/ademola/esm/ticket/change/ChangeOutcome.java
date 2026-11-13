package com.ademola.esm.ticket.change;

/** How a change went, recorded in its review. A fixed list so success rates can be reported on. */
public enum ChangeOutcome {
    SUCCESSFUL,
    SUCCESSFUL_WITH_ISSUES,
    FAILED,
    /** It went wrong and the backout plan was used to put things back as they were. */
    BACKED_OUT
}
