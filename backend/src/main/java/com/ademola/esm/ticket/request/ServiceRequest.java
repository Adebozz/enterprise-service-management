package com.ademola.esm.ticket.request;

import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemDraft;
import com.ademola.esm.ticket.WorkItemType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.util.UUID;

/** A standard request for something ("new laptop", "VPN access"). */
@Entity
@Table(name = "service_requests")
@DiscriminatorValue("SERVICE_REQUEST")
@PrimaryKeyJoinColumn(name = "work_item_id")
public class ServiceRequest extends WorkItem {

    @Column(name = "catalogue_item_id")
    private UUID catalogueItemId;

    @Column(name = "fulfilment_notes", columnDefinition = "text")
    private String fulfilmentNotes;

    protected ServiceRequest() {}

    public ServiceRequest(WorkItemDraft draft) {
        super(draft, ServiceRequestStatus.SUBMITTED.name());
    }

    @Override
    public WorkItemType getType() {
        return WorkItemType.SERVICE_REQUEST;
    }

    public ServiceRequestStatus getStatus() {
        return ServiceRequestStatus.valueOf(getStatusName());
    }

    public UUID getCatalogueItemId() {
        return catalogueItemId;
    }

    public String getFulfilmentNotes() {
        return fulfilmentNotes;
    }
}
