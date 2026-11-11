package com.ademola.esm.catalogue;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * The whole item, replaced (PUT). Requests already raised keep the questions and answers they were
 * submitted with, so changing the form is always safe.
 */
record UpdateCatalogueItemRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 200) String summary,
        @Nullable @Size(max = 4_000) String description,
        @NotNull UUID categoryId,
        @Nullable UUID subcategoryId,
        @NotNull @Size(max = CatalogueForm.MAX_FIELDS) List<@Valid @NotNull FormField> fields,
        @NotNull Boolean approvalRequired,
        @NotNull Boolean active,
        @NotNull Long version) {

    CatalogueItem.ItemDetails details() {
        return new CatalogueItem.ItemDetails(
                name, summary, description, categoryId, subcategoryId, fields, approvalRequired);
    }
}
