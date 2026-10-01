package com.ademola.esm.ticket;

/** Ticket types. Each has its own reference prefix and database sequence. */
public enum WorkItemType {
    INCIDENT("INC", "incident_ref_seq"),
    SERVICE_REQUEST("REQ", "service_request_ref_seq");

    private final String referencePrefix;
    private final String sequenceName;

    WorkItemType(String referencePrefix, String sequenceName) {
        this.referencePrefix = referencePrefix;
        this.sequenceName = sequenceName;
    }

    public String referencePrefix() {
        return referencePrefix;
    }

    String sequenceName() {
        return sequenceName;
    }
}
