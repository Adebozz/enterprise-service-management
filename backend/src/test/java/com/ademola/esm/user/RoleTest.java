package com.ademola.esm.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoleTest {

    @ParameterizedTest(name = "{0} includes {1} = {2}")
    @CsvSource({
        "ADMIN, TEAM_LEAD, true",
        "ADMIN, REQUESTER, true",
        "TEAM_LEAD, AGENT, true",
        "AGENT, AGENT, true",
        "AGENT, TEAM_LEAD, false",
        "REQUESTER, AGENT, false",
        "TEAM_LEAD, ADMIN, false"
    })
    void hierarchyIsStrictlyOrdered(Role role, Role other, boolean expected) {
        assertThat(role.includes(other)).isEqualTo(expected);
    }

    @Test
    void onlyRequesterIsNotStaff() {
        assertThat(Role.REQUESTER.isStaff()).isFalse();
        assertThat(Role.AGENT.isStaff()).isTrue();
        assertThat(Role.TEAM_LEAD.isStaff()).isTrue();
        assertThat(Role.ADMIN.isStaff()).isTrue();
    }

    @Test
    void springSecurityHierarchyIsGeneratedFromTheEnum() {
        assertThat(Role.springSecurityHierarchy())
                .isEqualTo("ROLE_ADMIN > ROLE_TEAM_LEAD\nROLE_TEAM_LEAD > ROLE_AGENT\nROLE_AGENT > ROLE_REQUESTER");
    }
}
