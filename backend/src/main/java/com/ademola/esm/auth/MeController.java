package com.ademola.esm.auth;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean passwordChangeEnabled;

    /**
     * @param passwordChangeEnabled off in the public demo (application-demo.yml), where everyone shares
     *     the demo accounts: one visitor changing Rita's password would lock every other visitor out
     */
    MeController(
            UserService userService,
            TeamService teamService,
            @Value("${esm.security.password-change-enabled:true}") boolean passwordChangeEnabled) {
        this.userService = userService;
        this.teamService = teamService;
        this.passwordChangeEnabled = passwordChangeEnabled;
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
        if (!passwordChangeEnabled) {
            throw new BusinessRuleException(
                    ErrorCode.ACCESS_DENIED, "Passwords can't be changed in the demo: the accounts are shared");
        }
        userService.changeOwnPassword(currentUser.id(), request.currentPassword(), request.newPassword());
    }
}
