package com.ademola.esm.ticket.problem;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Links an incident to the problem behind it; {@code version} is the incident's. */
public record LinkProblemRequest(
        @NotNull UUID problemId, @NotNull Long version) {}
