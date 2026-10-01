package com.ademola.esm.ticket.incident;

/** How an incident was resolved. A fixed list (not free text) so it can be reported on. */
public enum ResolutionCode {
    FIXED,
    WORKAROUND,
    NO_FAULT_FOUND,
    DUPLICATE,
    USER_ERROR,
    NOT_REPRODUCIBLE
}
