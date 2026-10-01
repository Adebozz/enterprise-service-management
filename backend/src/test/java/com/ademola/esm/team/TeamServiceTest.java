package com.ademola.esm.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ademola.esm.audit.AuditService;
import com.ademola.esm.common.error.DomainException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.user.Role;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserRoleChangedEvent;
import com.ademola.esm.user.UserService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

    @Mock
    TeamRepository teams;

    @Mock
    TeamMemberRepository members;

    @Mock
    UserService userService;

    @Mock
    AuditService audit;

    TeamService service;

    Team team;

    @BeforeEach
    void setUp() {
        service = new TeamService(teams, members, userService, Clock.fixed(NOW, ZoneOffset.UTC), audit);
        team = withId(new Team("Network Team", null));
    }

    @Test
    void addsActiveStaffMemberWithJoinTimeFromTheClock() {
        User agent = withId(new User("agent@example.com", "Agent", "h", Role.AGENT));
        when(teams.findById(team.getId())).thenReturn(Optional.of(team));
        when(userService.require(agent.getId())).thenReturn(agent);

        service.addMember(team.getId(), agent.getId());

        ArgumentCaptor<TeamMember> saved = ArgumentCaptor.forClass(TeamMember.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(new TeamMemberId(team.getId(), agent.getId()));
        assertThat(saved.getValue().getJoinedAt()).isEqualTo(NOW);
    }

    @Test
    void addingAnExistingMemberIsANoOp() {
        User agent = withId(new User("agent@example.com", "Agent", "h", Role.AGENT));
        when(teams.findById(team.getId())).thenReturn(Optional.of(team));
        when(userService.require(agent.getId())).thenReturn(agent);
        when(members.existsById(new TeamMemberId(team.getId(), agent.getId()))).thenReturn(true);

        service.addMember(team.getId(), agent.getId());

        verify(members, never()).save(any());
    }

    @Test
    void requestersCannotBeTeamMembers() {
        User requester = withId(new User("req@example.com", "Req", "h", Role.REQUESTER));
        when(teams.findById(team.getId())).thenReturn(Optional.of(team));
        when(userService.require(requester.getId())).thenReturn(requester);

        assertErrorCode(() -> service.addMember(team.getId(), requester.getId()), ErrorCode.USER_NOT_ELIGIBLE_FOR_TEAM);
    }

    @Test
    void inactiveUsersCannotBeAdded() {
        User agent = withId(new User("agent@example.com", "Agent", "h", Role.AGENT));
        ReflectionTestUtils.setField(agent, "active", false);
        when(teams.findById(team.getId())).thenReturn(Optional.of(team));
        when(userService.require(agent.getId())).thenReturn(agent);

        assertErrorCode(() -> service.addMember(team.getId(), agent.getId()), ErrorCode.USER_NOT_ELIGIBLE_FOR_TEAM);
    }

    @Test
    void inactiveTeamsCannotGainMembers() {
        team.deactivate();
        when(teams.findById(team.getId())).thenReturn(Optional.of(team));

        assertErrorCode(() -> service.addMember(team.getId(), UUID.randomUUID()), ErrorCode.TEAM_INACTIVE);
    }

    @Test
    void demotionToRequesterRemovesAndAuditsEveryMembership() {
        UUID userId = UUID.randomUUID();
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        when(members.findTeamIdsOf(userId)).thenReturn(java.util.List.of(teamA, teamB));

        service.onUserRoleChanged(new UserRoleChangedEvent(userId, Role.AGENT, Role.REQUESTER));

        verify(members).deleteById(new TeamMemberId(teamA, userId));
        verify(members).deleteById(new TeamMemberId(teamB, userId));
        verify(audit, org.mockito.Mockito.times(2)).record(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"AGENT", "TEAM_LEAD", "ADMIN"})
    void roleChangesBetweenStaffRolesKeepMemberships(Role newRole) {
        service.onUserRoleChanged(new UserRoleChangedEvent(UUID.randomUUID(), Role.AGENT, newRole));

        verify(members, never()).findTeamIdsOf(any());
        verify(members, never()).deleteById(any());
    }

    @Test
    void duplicateTeamNameIsRejectedCaseInsensitively() {
        when(teams.existsByNameIgnoreCase("network team")).thenReturn(true);

        assertErrorCode(
                () -> service.create(new CreateTeamRequest(" network team ", null)),
                ErrorCode.TEAM_NAME_ALREADY_EXISTS);
    }

    private static <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }

    private static void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(code);
    }
}
