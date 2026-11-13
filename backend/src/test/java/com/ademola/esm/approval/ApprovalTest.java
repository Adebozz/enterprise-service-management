package com.ademola.esm.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.ticket.change.ChangeStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalTest {

    static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    static final UUID LEAD = UUID.randomUUID();

    @Test
    void changesUseTheSameApprovalStatusesAsRequests() {
        for (String status :
                new String[] {ApprovalService.AWAITING, ApprovalService.APPROVED, ApprovalService.REJECTED}) {
            assertThat(ChangeStatus.valueOf(status).name()).isEqualTo(status);
        }
    }

    @Test
    void approvingRecordsWhoWhenAndAnOptionalComment() {
        Approval approval = new Approval(UUID.randomUUID());

        approval.approve(LEAD, NOW, "  ");

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getDecidedBy()).isEqualTo(LEAD);
        assertThat(approval.getDecidedAt()).isEqualTo(NOW);
        assertThat(approval.getComment()).isNull();
        assertThat(approval.isPending()).isFalse();
    }

    @Test
    void rejectingNeedsAReason() {
        Approval approval = new Approval(UUID.randomUUID());

        assertThatThrownBy(() -> approval.reject(LEAD, NOW, " "))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code())
                .isEqualTo(ErrorCode.APPROVAL_REASON_REQUIRED);
        assertThat(approval.isPending()).isTrue();

        approval.reject(LEAD, NOW, " Read access is enough ");
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(approval.getComment()).isEqualTo("Read access is enough");
    }

    @Test
    void aWithdrawalHasNoDecider() {
        Approval approval = new Approval(UUID.randomUUID());

        approval.withdraw(NOW);

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(approval.getDecidedBy()).isNull();
        assertThat(approval.getDecidedAt()).isEqualTo(NOW);
    }

    @Test
    void aDecisionIsFinal() {
        Approval approval = new Approval(UUID.randomUUID());
        approval.approve(LEAD, NOW, null);

        assertThatThrownBy(() -> approval.reject(LEAD, NOW, "Changed my mind"))
                .extracting(e -> ((BusinessRuleException) e).code())
                .isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED);
        assertThatThrownBy(() -> approval.withdraw(NOW))
                .extracting(e -> ((BusinessRuleException) e).code())
                .isEqualTo(ErrorCode.APPROVAL_ALREADY_DECIDED);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }
}
