package com.ademola.esm.team;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: a {@code null} field means "leave unchanged". {@code version} is mandatory. */
public record UpdateTeamRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String name,

        @Size(max = 500) String description,
        Boolean active,
        @NotNull Long version) {}
