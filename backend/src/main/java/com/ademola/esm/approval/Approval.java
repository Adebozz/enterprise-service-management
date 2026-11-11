package com.ademola.esm.approval;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The sign-off a service request needs before work starts. Decided once: pending, then approved,
 * rejected or withdrawn, never changed again.
 */
@Entity
@Table(name = "approvals")
class Approval extends BaseEntity {

    @Column(name = "work_item_id", nullable = false, updatable = false)
    private UUID workItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(columnDefinition = "text")
    private String comment;

    protected Approval() {}

    Approval(UUID workItemId) {
        this.workItemId = workItemId;
    }

    void approve(UUID approver, Instant at, String comment) {
        decide(ApprovalStatus.APPROVED, approver, at, comment);
    }

    void reject(UUID approver, Instant at, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException(ErrorCode.APPROVAL_REASON_REQUIRED, "Say why the request is rejected");
        }
        decide(ApprovalStatus.REJECTED, approver, at, reason);
    }

    void withdraw(Instant at) {
        decide(ApprovalStatus.WITHDRAWN, null, at, null);
    }

    boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }

    private void decide(ApprovalStatus outcome, UUID by, Instant at, String text) {
        if (!isPending()) {
            throw new BusinessRuleException(
                    ErrorCode.APPROVAL_ALREADY_DECIDED,
                    "This approval was already " + status.name().toLowerCase());
        }
        this.status = outcome;
        this.decidedBy = by;
        this.decidedAt = at;
        this.comment = text == null || text.isBlank() ? null : text.trim();
    }

    UUID getWorkItemId() {
        return workItemId;
    }

    ApprovalStatus getStatus() {
        return status;
    }

    UUID getDecidedBy() {
        return decidedBy;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    String getComment() {
        return comment;
    }
}
