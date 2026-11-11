package com.ademola.esm.ticket.problem;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The whole analysis as it should now read; a blank field clears it. */
public record UpdateProblemAnalysisRequest(
        @Nullable @Size(max = 10_000) String rootCause,
        @Nullable @Size(max = 10_000) String workaround,
        @NotNull Long version) {}
