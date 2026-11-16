package com.ademola.esm.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeControllerTest {

    final CurrentUser rita = new CurrentUser(UUID.randomUUID(), Role.REQUESTER, "Rita");
    final ChangePasswordRequest change = new ChangePasswordRequest("old-password-123", "new-password-456");

    @Test
    void inTheDemoNobodyCanChangeASharedAccountsPassword() {
        UserService users = mock(UserService.class);
        MeController demo = new MeController(users, mock(TeamService.class), false);

        assertThatThrownBy(() -> demo.changePassword(rita, change))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("demo");
        verifyNoInteractions(users);
    }

    @Test
    void elsewherePasswordChangesGoThrough() {
        UserService users = mock(UserService.class);

        new MeController(users, mock(TeamService.class), true).changePassword(rita, change);

        verify(users).changeOwnPassword(rita.id(), "old-password-123", "new-password-456");
    }
}
