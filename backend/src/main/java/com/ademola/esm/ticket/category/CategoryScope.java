package com.ademola.esm.ticket.category;

import com.ademola.esm.ticket.WorkItemType;

/** Which ticket types a category can be used for. */
public enum CategoryScope {
    INCIDENT,
    SERVICE_REQUEST,
    ANY;

    public boolean includes(WorkItemType type) {
        return this == ANY || name().equals(type.name());
    }

    /** A subcategory may not be broader than its parent. */
    boolean narrowerOrEqualTo(CategoryScope parent) {
        return parent == ANY || parent == this;
    }
}
