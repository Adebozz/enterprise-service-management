package com.ademola.esm.auth;

import com.ademola.esm.team.TeamService;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own profile. Identity always comes from the token ({@link CurrentUser}),
 * never from a path or body parameter, so there's no way to address another user here.
 */
@RestController
@RequestMapping("/api/users/me")
@Tag(name = "Current user")
class MeController {

    private final UserService userService;
    private final TeamService teamService;

    MeController(UserService userService, TeamService teamService) {
        this.userService = userService;
        this.teamService = teamService;
    }

    @GetMapping
    @Operation(operationId = "getMyProfile", summary = "Profile of the signed-in user, including their teams")
    MyProfileResponse me(@AuthenticationPrincipal CurrentUser currentUser) {
        User user = userService.require(currentUser.id());
        return new MyProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                teamService.teamsOf(user.getId()));
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            operationId = "changeMyPassword",
            summary = "Change own password. Signs out all sessions; the client must sign in again")
    void changePassword(
            @AuthenticationPrincipal CurrentUser currentUser, @Valid @RequestBody ChangePasswordRequest request) {
        userService.changeOwnPassword(currentUser.id(), request.currentPassword(), request.newPassword());
    }
}
