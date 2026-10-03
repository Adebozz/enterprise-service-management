package com.ademola.esm.team;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read access to teams for support staff (e.g. to populate assignment drop-downs). */
@RestController
@RequestMapping("/api/teams")
@Tag(name = "Teams")
class TeamController {

    private final TeamService teamService;

    TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    @Operation(operationId = "listTeams", summary = "List active teams")
    List<TeamResponse> list() {
        return teamService.listActive();
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getTeam", summary = "Get an active team")
    TeamResponse get(@PathVariable UUID id) {
        return teamService.get(id);
    }

    @GetMapping("/{id}/members")
    @Operation(operationId = "listTeamMembers", summary = "List members of a team")
    List<TeamMemberResponse> members(@PathVariable UUID id) {
        return teamService.members(id);
    }
}
