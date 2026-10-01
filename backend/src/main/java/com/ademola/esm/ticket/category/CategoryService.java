package com.ademola.esm.ticket.category;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.ticket.WorkItemType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CategoryService {

    private final CategoryRepository categories;
    private final TeamService teams;
    private final AuditService audit;

    public CategoryService(CategoryRepository categories, TeamService teams, AuditService audit) {
        this.categories = categories;
        this.teams = teams;
        this.audit = audit;
    }

    // ----- reading ------------------------------------------------------------------------------

    /** Active categories usable for a ticket type, as a tree (for the "raise a ticket" form). */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<CategoryResponse> activeTree(WorkItemType type) {
        return tree(c -> c.isActive() && c.appliesTo(type));
    }

    /** Every category including inactive ones, for administration. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<CategoryResponse> fullTree() {
        return tree(c -> true);
    }

    /** Display name lookup for other ticket packages. */
    @Transactional(readOnly = true)
    public String nameOf(UUID categoryId) {
        return require(categoryId).getName();
    }

    /**
     * Validates the category chosen for a new ticket and resolves the team it routes to. Errors
     * are 422 (the request is well-formed but the choice is not allowed), never 404, so clients
     * can't use this to probe for category ids.
     */
    @Transactional(readOnly = true)
    public CategoryChoice chooseForNewTicket(UUID categoryId, UUID subcategoryId, WorkItemType type) {
        Category category = categories
                .findById(categoryId)
                .filter(c -> c.isTopLevel() && c.isActive() && c.appliesTo(type))
                .orElseThrow(() -> invalidCategory("Category is not available for " + type));
        if (subcategoryId == null) {
            return new CategoryChoice(category.getId(), null, category.getDefaultTeamId());
        }
        Category subcategory = categories
                .findById(subcategoryId)
                .filter(s -> category.getId().equals(s.getParentId()) && s.isActive() && s.appliesTo(type))
                .orElseThrow(() -> invalidCategory("Subcategory does not belong to the chosen category"));
        UUID team =
                subcategory.getDefaultTeamId() != null ? subcategory.getDefaultTeamId() : category.getDefaultTeamId();
        return new CategoryChoice(category.getId(), subcategory.getId(), team);
    }

    // ----- administration -----------------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse create(CreateCategoryRequest request) {
        if (categories.existsByCode(request.code())) {
            throw codeExists();
        }
        if (request.parentId() == null && request.defaultTeamId() == null) {
            throw new BusinessRuleException(
                    ErrorCode.CATEGORY_TEAM_REQUIRED, "A top-level category must route to a default team");
        }
        if (request.parentId() != null) {
            Category parent = require(request.parentId());
            if (!parent.isTopLevel()) {
                throw invalidCategory("Categories are limited to two levels; the parent must be top-level");
            }
            if (!request.appliesTo().narrowerOrEqualTo(parent.getAppliesTo())) {
                throw invalidCategory(
                        "Subcategory scope must match its parent's (%s)".formatted(parent.getAppliesTo()));
            }
        }
        if (request.defaultTeamId() != null) {
            teams.requireActiveTeam(request.defaultTeamId());
        }

        Category category = new Category(
                request.code(), request.name(), request.parentId(), request.defaultTeamId(), request.appliesTo());
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException raceLost) {
            throw codeExists();
        }
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("code", category.getCode());
        created.put("name", category.getName());
        created.put("parentId", category.getParentId());
        created.put("defaultTeamId", category.getDefaultTeamId());
        created.put("appliesTo", category.getAppliesTo());
        audit.record(
                AuditRecord.created(AuditAction.CATEGORY_CREATED, AuditEntityType.CATEGORY, category.getId(), created));
        return CategoryResponse.of(category, List.of());
    }

    @PreAuthorize("hasRole('ADMIN')")
    public CategoryResponse update(UUID id, UpdateCategoryRequest request) {
        Category category = require(id);
        StaleVersionException.check("Category", request.version(), category.getVersion());

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (request.name() != null && !request.name().trim().equals(category.getName())) {
            before.put("name", category.getName());
            category.rename(request.name());
            after.put("name", category.getName());
        }
        if (request.defaultTeamId() != null && !request.defaultTeamId().equals(category.getDefaultTeamId())) {
            teams.requireActiveTeam(request.defaultTeamId());
            before.put("defaultTeamId", category.getDefaultTeamId());
            category.routeTo(request.defaultTeamId());
            after.put("defaultTeamId", category.getDefaultTeamId());
        }
        if (request.active() != null && request.active() != category.isActive()) {
            before.put("active", category.isActive());
            if (request.active()) {
                category.activate();
            } else {
                category.deactivate();
            }
            after.put("active", category.isActive());
        }
        if (!after.isEmpty()) {
            audit.record(
                    AuditRecord.changed(AuditAction.CATEGORY_UPDATED, AuditEntityType.CATEGORY, id, before, after));
        }
        categories.flush(); // return the incremented version
        return CategoryResponse.of(category, List.of());
    }

    private List<CategoryResponse> tree(Predicate<Category> include) {
        List<Category> all =
                categories.findAllByOrderByNameAsc().stream().filter(include).toList();
        return all.stream()
                .filter(Category::isTopLevel)
                .map(parent -> CategoryResponse.of(
                        parent,
                        all.stream()
                                .filter(child -> Objects.equals(child.getParentId(), parent.getId()))
                                .map(child -> CategoryResponse.of(child, List.of()))
                                .toList()))
                .toList();
    }

    private Category require(UUID id) {
        return categories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", id));
    }

    private static BusinessRuleException invalidCategory(String message) {
        return new BusinessRuleException(ErrorCode.INVALID_CATEGORY, message);
    }

    private static BusinessRuleException codeExists() {
        return new BusinessRuleException(
                ErrorCode.CATEGORY_CODE_ALREADY_EXISTS, "A category with this code already exists");
    }
}
