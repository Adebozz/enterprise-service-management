package com.ademola.esm.ticket.category;

import com.ademola.esm.ticket.WorkItemType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Categories")
class CategoryController {

    private final CategoryService categoryService;

    CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/api/categories")
    @Operation(
            operationId = "listCategories",
            summary = "Active categories (with subcategories) usable for a ticket type")
    List<CategoryResponse> list(@RequestParam WorkItemType type) {
        return categoryService.activeTree(type);
    }

    @GetMapping("/api/admin/categories")
    @Operation(operationId = "listAllCategories", summary = "All categories, including inactive ones")
    List<CategoryResponse> listAll() {
        return categoryService.fullTree();
    }

    @PostMapping("/api/admin/categories")
    @Operation(operationId = "createCategory", summary = "Create a category or subcategory")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        CategoryResponse created = categoryService.create(request);
        return ResponseEntity.created(URI.create("/api/admin/categories/" + created.id()))
                .body(created);
    }

    @PatchMapping("/api/admin/categories/{id}")
    @Operation(
            operationId = "updateCategory",
            summary = "Rename, re-route or (de)activate a category (requires current version)")
    CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
        return categoryService.update(id, request);
    }
}
