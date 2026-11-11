package com.ademola.esm.catalogue;

import com.ademola.esm.ticket.query.NamedRef;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** A catalogue item with its form. Requesters and admins get the same shape. */
public record CatalogueItemResponse(
        UUID id,
        String name,
        String summary,
        @Nullable String description,
        NamedRef category,
        @Nullable NamedRef subcategory,
        List<FormField> fields,
        boolean approvalRequired,
        boolean active,
        long version) {}
