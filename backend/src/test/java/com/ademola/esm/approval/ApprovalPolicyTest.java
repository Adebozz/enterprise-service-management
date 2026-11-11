package com.ademola.esm.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.user.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalPolicyTest {

    static final UUID TEAM = UUID.randomUUID();
    static final UUID OTHER_TEAM = UUID.randomUUID();
    static final UUID REQUESTER = UUID.randomUUID();

    @Test
    void aTeamLeadOfTheRequestsTeamDecides() {
        assertThat(ApprovalPolicy.canDecide(user(Role.TEAM_LEAD), REQUESTER, TEAM, Set.of(TEAM)))
                .isTrue();
    }

    @Test
    void aTeamLeadOfAnotherTeamDoesNot() {
        assertThat(ApprovalPolicy.canDecide(user(Role.TEAM_LEAD), REQUESTER, TEAM, Set.of(OTHER_TEAM)))
                .isFalse();
    }

    @Test
    void agentsAndRequestersNeverDecide() {
        assertThat(ApprovalPolicy.canDecide(user(Role.AGENT), REQUESTER, TEAM, Set.of(TEAM)))
                .isFalse();
        assertThat(ApprovalPolicy.canDecide(user(Role.REQUESTER), REQUESTER, TEAM, Set.of()))
                .isFalse();
    }

    @Test
    void adminsDecideForAnyTeam() {
        assertThat(ApprovalPolicy.canDecide(user(Role.ADMIN), REQUESTER, TEAM, Set.of()))
                .isTrue();
    }

    @Test
    void nobodyApprovesTheirOwnRequestWhateverTheirRole() {
        assertThat(ApprovalPolicy.canDecide(
                        new CurrentUser(REQUESTER, Role.ADMIN, "Ada"), REQUESTER, TEAM, Set.of(TEAM)))
                .isFalse();
        assertThat(ApprovalPolicy.canDecide(
                        new CurrentUser(REQUESTER, Role.TEAM_LEAD, "Leo"), REQUESTER, TEAM, Set.of(TEAM)))
                .isFalse();
    }

    private static CurrentUser user(Role role) {
        return new CurrentUser(UUID.randomUUID(), role, role.name());
    }
}
