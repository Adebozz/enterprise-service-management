package com.ademola.esm.catalogue;

import com.ademola.esm.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Something requesters can order ("New laptop", "Shared drive access"), with the questions to ask
 * and the category that routes it to a team. Deactivated rather than deleted, because requests
 * already raised point at it.
 */
@Entity
@Table(name = "catalogue_items")
class CatalogueItem extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 200)
    private String summary;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "subcategory_id")
    private UUID subcategoryId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_fields", nullable = false)
    private List<FormField> fields;

    @Column(nullable = false)
    private boolean active = true;

    protected CatalogueItem() {}

    CatalogueItem(ItemDetails details) {
        apply(details);
    }

    /** Everything an admin edits, replaced as a whole. */
    record ItemDetails(
            String name,
            String summary,
            String description,
            UUID categoryId,
            UUID subcategoryId,
            List<FormField> fields) {}

    void apply(ItemDetails details) {
        this.name = details.name().trim();
        this.summary = details.summary().trim();
        this.description =
                details.description() == null || details.description().isBlank()
                        ? null
                        : details.description().trim();
        this.categoryId = details.categoryId();
        this.subcategoryId = details.subcategoryId();
        this.fields = List.copyOf(details.fields());
    }

    void setActive(boolean active) {
        this.active = active;
    }

    String getName() {
        return name;
    }

    String getSummary() {
        return summary;
    }

    String getDescription() {
        return description;
    }

    UUID getCategoryId() {
        return categoryId;
    }

    UUID getSubcategoryId() {
        return subcategoryId;
    }

    List<FormField> getFields() {
        return fields;
    }

    boolean isActive() {
        return active;
    }
}
