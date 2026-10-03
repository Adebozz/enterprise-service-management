package com.ademola.esm.ticket.category;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** Category with its subcategories (empty for subcategories themselves). */
public record CategoryResponse(
        UUID id,
        String code,
        String name,
        @Nullable UUID parentId,
        @Nullable UUID defaultTeamId,
        CategoryScope appliesTo,
        boolean active,
        long version,
        List<CategoryResponse> subcategories) {

    static CategoryResponse of(Category category, List<CategoryResponse> subcategories) {
        return new CategoryResponse(
                category.getId(),
                category.getCode(),
                category.getName(),
                category.getParentId(),
                category.getDefaultTeamId(),
                category.getAppliesTo(),
                category.isActive(),
                category.getVersion(),
                subcategories);
    }
}
