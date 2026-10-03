package com.ademola.esm.team;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/teams")
@Tag(name = "Admin: teams")
class AdminTeamController {

    private final TeamService teamService;

    AdminTeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    @Operation(operationId = "listAllTeams", summary = "List all teams, including inactive ones")
    List<TeamResponse> list() {
        return teamService.listAll();
    }

    @PostMapping
    @Operation(operationId = "createTeam", summary = "Create a team")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<TeamResponse> create(@Valid @RequestBody CreateTeamRequest request) {
        TeamResponse created = teamService.create(request);
        return ResponseEntity.created(URI.create("/api/teams/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @Operation(
            operationId = "updateTeam",
            summary = "Rename, describe, activate or deactivate a team (requires current version)")
    TeamResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTeamRequest request) {
        return teamService.update(id, request);
    }

    // PUT/DELETE on the membership resource are naturally idempotent: repeating them is harmless.
    @PutMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "addTeamMember", summary = "Add a user to a team (idempotent)")
    void addMember(@PathVariable UUID id, @PathVariable UUID userId) {
        teamService.addMember(id, userId);
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removeTeamMember", summary = "Remove a user from a team (idempotent)")
    void removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        teamService.removeMember(id, userId);
    }
}
