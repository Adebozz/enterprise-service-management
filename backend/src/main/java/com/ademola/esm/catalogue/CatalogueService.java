package com.ademola.esm.catalogue;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.category.CategoryService;
import com.ademola.esm.ticket.query.NamedRef;
import com.ademola.esm.user.Role;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading the catalogue (everyone) and maintaining it (admins). */
@Service
@Transactional
public class CatalogueService {

    private final CatalogueItemRepository items;
    private final CategoryService categories;
    private final AuditService audit;

    CatalogueService(CatalogueItemRepository items, CategoryService categories, AuditService audit) {
        this.items = items;
        this.categories = categories;
        this.audit = audit;
    }

    // ----- reading ------------------------------------------------------------------------------

    /** What requesters can order, by name. */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<CatalogueItemResponse> listActive() {
        return responses(items.findAllByActiveTrueOrderByNameAsc());
    }

    /** Every item, including withdrawn ones. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<CatalogueItemResponse> listAll() {
        return responses(items.findAllByOrderByNameAsc());
    }

    /** One item with its form. A withdrawn item is 404 for everyone but admins. */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public CatalogueItemResponse get(CurrentUser user, UUID id) {
        CatalogueItem item = items.findById(id)
                .filter(found -> found.isActive() || user.role() == Role.ADMIN)
                .orElseThrow(() -> new ResourceNotFoundException("Catalogue item", id));
        return responses(List.of(item)).getFirst();
    }

    /** An item requesters may order now, for the request flow in this module. */
    CatalogueItem requireOrderable(UUID id) {
        return items.findById(id)
                .filter(CatalogueItem::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Catalogue item", id));
    }

    // ----- administration -----------------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    public CatalogueItemResponse create(CreateCatalogueItemRequest request) {
        CatalogueItem.ItemDetails details = request.details();
        validate(details);
        if (items.existsByNameIgnoreCase(details.name().trim())) {
            throw nameExists();
        }
        CatalogueItem item = new CatalogueItem(details);
        try {
            items.saveAndFlush(item);
        } catch (DataIntegrityViolationException raceLost) {
            throw nameExists();
        }
        audit.record(AuditRecord.created(
                AuditAction.CATALOGUE_ITEM_CREATED, AuditEntityType.CATALOGUE_ITEM, item.getId(), auditView(item)));
        return responses(List.of(item)).getFirst();
    }

    @PreAuthorize("hasRole('ADMIN')")
    public CatalogueItemResponse update(UUID id, UpdateCatalogueItemRequest request) {
        CatalogueItem item = items.findById(id).orElseThrow(() -> new ResourceNotFoundException("Catalogue item", id));
        StaleVersionException.check("Catalogue item", request.version(), item.getVersion());
        CatalogueItem.ItemDetails details = request.details();
        validate(details);
        if (items.existsByNameIgnoreCaseAndIdNot(details.name().trim(), id)) {
            throw nameExists();
        }

        Map<String, Object> before = auditView(item);
        item.apply(details);
        item.setActive(request.active());
        Map<String, Object> after = auditView(item);
        changedOnly(before, after);
        if (!after.isEmpty()) {
            audit.record(AuditRecord.changed(
                    AuditAction.CATALOGUE_ITEM_UPDATED, AuditEntityType.CATALOGUE_ITEM, id, before, after));
        }
        try {
            items.flush(); // return the incremented version; a concurrent rename surfaces here
        } catch (DataIntegrityViolationException raceLost) {
            throw nameExists();
        }
        return responses(List.of(item)).getFirst();
    }

    // ----- helpers ------------------------------------------------------------------------------

    /** The category must accept service requests now; the form must make sense as a whole. */
    private void validate(CatalogueItem.ItemDetails details) {
        categories.chooseForNewTicket(details.categoryId(), details.subcategoryId(), WorkItemType.SERVICE_REQUEST);
        CatalogueForm.checkDefinition(details.fields());
    }

    private List<CatalogueItemResponse> responses(List<CatalogueItem> found) {
        Set<UUID> categoryIds = new HashSet<>();
        found.forEach(item -> {
            categoryIds.add(item.getCategoryId());
            if (item.getSubcategoryId() != null) {
                categoryIds.add(item.getSubcategoryId());
            }
        });
        Map<UUID, String> names = categories.namesOf(categoryIds);
        return found.stream()
                .map(item -> new CatalogueItemResponse(
                        item.getId(),
                        item.getName(),
                        item.getSummary(),
                        item.getDescription(),
                        new NamedRef(item.getCategoryId(), names.get(item.getCategoryId())),
                        item.getSubcategoryId() == null
                                ? null
                                : new NamedRef(item.getSubcategoryId(), names.get(item.getSubcategoryId())),
                        item.getFields(),
                        item.isActive(),
                        item.getVersion()))
                .toList();
    }

    /** Small enough for the audit trail: the form is summarised by its question keys. */
    private static Map<String, Object> auditView(CatalogueItem item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", item.getName());
        view.put("summary", item.getSummary());
        view.put("description", item.getDescription());
        view.put("categoryId", item.getCategoryId());
        view.put("subcategoryId", item.getSubcategoryId());
        view.put("fields", item.getFields().stream().map(FormField::key).toList());
        view.put("active", item.isActive());
        return view;
    }

    /** Keeps only the entries that differ (in both maps), so the audit entry shows just the change. */
    private static void changedOnly(Map<String, Object> before, Map<String, Object> after) {
        Set<String> same = new HashSet<>();
        before.forEach((key, value) -> {
            if (Objects.equals(value, after.get(key))) {
                same.add(key);
            }
        });
        before.keySet().removeAll(same);
        after.keySet().removeAll(same);
    }

    private static BusinessRuleException nameExists() {
        return new BusinessRuleException(
                ErrorCode.CATALOGUE_ITEM_NAME_ALREADY_EXISTS, "A catalogue item with this name already exists");
    }
}
