package com.ademola.esm.ticket.category;

import com.ademola.esm.ticket.WorkItemType;

/** Which ticket types a category can be used for. */
public enum CategoryScope {
    INCIDENT,
    SERVICE_REQUEST,
    ANY;

    /**
     * Problems and changes are filed under incident categories: a problem explains incidents and a
     * change alters the service they happen in, so both belong where the incidents do, and are routed
     * to the team that runs that service.
     */
    public boolean includes(WorkItemType type) {
        WorkItemType filedAs =
                type == WorkItemType.PROBLEM || type == WorkItemType.CHANGE ? WorkItemType.INCIDENT : type;
        return this == ANY || name().equals(filedAs.name());
    }

    /** A subcategory may not be broader than its parent. */
    boolean narrowerOrEqualTo(CategoryScope parent) {
        return parent == ANY || parent == this;
    }
}
