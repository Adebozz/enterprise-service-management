package com.ademola.esm.catalogue;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** A new item. The category (and optional subcategory) must accept service requests. */
public record CreateCatalogueItemRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 200) String summary,
        @Nullable @Size(max = 4_000) String description,
        @NotNull UUID categoryId,
        @Nullable UUID subcategoryId,
        @NotNull @Size(max = CatalogueForm.MAX_FIELDS) List<@Valid @NotNull FormField> fields) {

    CatalogueItem.ItemDetails details() {
        return new CatalogueItem.ItemDetails(name, summary, description, categoryId, subcategoryId, fields);
    }
}
