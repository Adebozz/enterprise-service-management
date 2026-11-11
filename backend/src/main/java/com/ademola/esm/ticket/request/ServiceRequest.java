package com.ademola.esm.ticket.request;

import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemDraft;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.workflow.TransitionInput;
import com.ademola.esm.ticket.workflow.WorkflowDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A standard request for something ("new laptop", "VPN access"). */
@Entity
@Table(name = "service_requests")
@DiscriminatorValue("SERVICE_REQUEST")
@PrimaryKeyJoinColumn(name = "work_item_id")
public class ServiceRequest extends WorkItem {

    @Column(name = "catalogue_item_id")
    private UUID catalogueItemId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "catalogue_answers", updatable = false)
    private List<RequestAnswer> catalogueAnswers;

    @Column(name = "fulfilment_notes", columnDefinition = "text")
    private String fulfilmentNotes;

    protected ServiceRequest() {}

    public ServiceRequest(WorkItemDraft draft) {
        super(draft, ServiceRequestStatus.SUBMITTED.name());
    }

    /** A request ordered from the catalogue: remembers the item and what the requester answered. */
    public ServiceRequest(WorkItemDraft draft, UUID catalogueItemId, List<RequestAnswer> answers) {
        this(draft);
        this.catalogueItemId = catalogueItemId;
        this.catalogueAnswers = List.copyOf(answers);
    }

    @Override
    public WorkItemType getType() {
        return WorkItemType.SERVICE_REQUEST;
    }

    @Override
    public WorkflowDefinition<ServiceRequestStatus> workflow() {
        return ServiceRequestWorkflow.DEFINITION;
    }

    @Override
    protected void onTransition(String from, String to, TransitionInput input, Instant now) {
        ServiceRequestStatus previous = ServiceRequestStatus.valueOf(from);
        switch (ServiceRequestStatus.valueOf(to)) {
            case IN_PROGRESS -> {
                if (previous == ServiceRequestStatus.FULFILLED) {
                    fulfilmentNotes = null; // reopened: what was delivered wasn't right
                    clearResolved();
                }
                recordFirstResponse(now);
            }
            case FULFILLED -> {
                fulfilmentNotes = input.notes().trim();
                markResolved(now); // resolved_at doubles as "fulfilled at" for SLA/reporting
            }
            case CLOSED, CANCELLED, REJECTED -> markClosed(now);
            default -> {
                // SUBMITTED, APPROVAL_PENDING, APPROVED: status change only
            }
        }
    }

    @Override
    protected boolean isAssignable() {
        return switch (getStatus()) {
            case SUBMITTED, APPROVAL_PENDING, APPROVED, IN_PROGRESS -> true;
            case FULFILLED, CLOSED, CANCELLED, REJECTED -> false;
        };
    }

    @Override
    protected void onAssignmentChanged(boolean hadAssignee, boolean hasAssignee, Instant now) {
        if (hadAssignee && !hasAssignee && getStatus() == ServiceRequestStatus.IN_PROGRESS) {
            transition(ServiceRequestStatus.SUBMITTED.name(), TransitionInput.NONE, now); // return to queue
        }
    }

    public ServiceRequestStatus getStatus() {
        return ServiceRequestStatus.valueOf(getStatusName());
    }

    public UUID getCatalogueItemId() {
        return catalogueItemId;
    }

    /** Empty for requests not raised from the catalogue. */
    public List<RequestAnswer> getCatalogueAnswers() {
        return catalogueAnswers == null ? List.of() : catalogueAnswers;
    }

    public String getFulfilmentNotes() {
        return fulfilmentNotes;
    }
}
