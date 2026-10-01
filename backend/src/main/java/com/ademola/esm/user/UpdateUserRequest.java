package com.ademola.esm.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: a {@code null} field means "leave unchanged". {@code version} is mandatory. */
public record UpdateUserRequest(
        @Size(max = 120) @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String displayName,

        Role role,
        Boolean active,
        @NotNull Long version) {}
