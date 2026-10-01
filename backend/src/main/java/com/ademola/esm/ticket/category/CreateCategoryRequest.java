package com.ademola.esm.ticket.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateCategoryRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$", message = "must be UPPER_SNAKE_CASE, 2-50 characters")
        String code,

        @NotBlank @Size(max = 100) String name,
        UUID parentId,
        UUID defaultTeamId,
        @NotNull CategoryScope appliesTo) {}
