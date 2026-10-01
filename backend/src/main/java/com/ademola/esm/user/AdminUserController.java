package com.ademola.esm.user;

import com.ademola.esm.common.web.PageResponse;
import com.ademola.esm.common.web.SortableFields;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Admin: users")
class AdminUserController {

    private static final SortableFields SORTABLE = SortableFields.of("email", "displayName", "role", "createdAt");

    private final UserService userService;

    AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @Operation(summary = "Create a user with an initial password")
    ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.create(request);
        return ResponseEntity.created(URI.create("/api/admin/users/" + created.id()))
                .body(created);
    }

    @GetMapping
    @Operation(summary = "Search users by email/name, role and active flag")
    PageResponse<UserResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20, sort = "displayName", direction = Sort.Direction.ASC) Pageable pageable) {
        return userService.search(new UserSearchCriteria(q, role, active), SORTABLE.validate(pageable));
    }

    @GetMapping("/{id}")
    UserResponse get(@PathVariable UUID id) {
        return userService.get(id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename, change role, activate or deactivate a user (requires current version)")
    UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }
}
