package com.ademola.esm.approval;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A comment is optional when approving and required when rejecting; the requester sees it. */
public record ApprovalDecisionRequest(
        @NotNull Decision decision,
        @Nullable @Size(max = 2_000) String comment,
        @NotNull Long version) {

    public enum Decision {
        APPROVE,
        REJECT
    }
}
