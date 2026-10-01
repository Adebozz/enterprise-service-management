package com.ademola.esm.ticket.incident;

import com.ademola.esm.ticket.WorkItem;
import com.ademola.esm.ticket.WorkItemDraft;
import com.ademola.esm.ticket.WorkItemType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;

/** An unplanned interruption or degradation of a service ("payroll is down"). */
@Entity
@Table(name = "incidents")
@DiscriminatorValue("INCIDENT")
@PrimaryKeyJoinColumn(name = "work_item_id")
public class Incident extends WorkItem {

    @Column(name = "affected_service", length = 120)
    private String affectedService;

    @Column(name = "resolution_code", length = 40)
    private String resolutionCode;

    @Column(name = "resolution_notes", columnDefinition = "text")
    private String resolutionNotes;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    protected Incident() {}

    public Incident(WorkItemDraft draft, String affectedService) {
        super(draft, IncidentStatus.NEW.name());
        this.affectedService = affectedService;
    }

    @Override
    public WorkItemType getType() {
        return WorkItemType.INCIDENT;
    }

    public IncidentStatus getStatus() {
        return IncidentStatus.valueOf(getStatusName());
    }

    public String getAffectedService() {
        return affectedService;
    }

    public String getResolutionCode() {
        return resolutionCode;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public int getReopenCount() {
        return reopenCount;
    }
}
