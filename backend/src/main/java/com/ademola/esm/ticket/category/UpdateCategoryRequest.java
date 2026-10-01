package com.ademola.esm.ticket.category;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Partial update ({@code null} = unchanged). Code, parent and scope are fixed after creation. */
public record UpdateCategoryRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String name,

        UUID defaultTeamId,
        Boolean active,
        @NotNull Long version) {}
