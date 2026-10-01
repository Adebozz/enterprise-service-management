package com.ademola.esm.ticket.priority;

/** Derived from impact x urgency by {@link PriorityPolicy}; drives SLA targets in Phase 2. */
public enum Priority {
    P1("Critical"),
    P2("High"),
    P3("Medium"),
    P4("Low");

    private final String label;

    Priority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
