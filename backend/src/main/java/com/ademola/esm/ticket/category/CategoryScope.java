package com.ademola.esm.ticket.category;

import com.ademola.esm.ticket.WorkItemType;

/** Which ticket types a category can be used for. */
public enum CategoryScope {
    INCIDENT,
    SERVICE_REQUEST,
    ANY;

    /**
     * Problems are filed under incident categories: a problem explains incidents, so it belongs where
     * they do, and is routed to the team that handles them.
     */
    public boolean includes(WorkItemType type) {
        WorkItemType filedAs = type == WorkItemType.PROBLEM ? WorkItemType.INCIDENT : type;
        return this == ANY || name().equals(filedAs.name());
    }

    /** A subcategory may not be broader than its parent. */
    boolean narrowerOrEqualTo(CategoryScope parent) {
        return parent == ANY || parent == this;
    }
}
