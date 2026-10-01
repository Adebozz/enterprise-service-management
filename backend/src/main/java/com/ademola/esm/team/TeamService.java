package com.ademola.esm.team;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserRoleChangedEvent;
import com.ademola.esm.user.UserService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final UserService userService;
    private final Clock clock;

    public TeamService(TeamRepository teams, TeamMemberRepository members, UserService userService, Clock clock) {
        this.teams = teams;
        this.members = members;
        this.userService = userService;
        this.clock = clock;
    }

    // ----- queries (support staff) --------------------------------------------------------------

    @PreAuthorize("hasRole('AGENT')")
    @Transactional(readOnly = true)
    public List<TeamResponse> listActive() {
        return teams.findAllByActiveTrueOrderByNameAsc().stream()
                .map(TeamResponse::from)
                .toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<TeamResponse> listAll() {
        return teams.findAllByOrderByNameAsc().stream().map(TeamResponse::from).toList();
    }

    @PreAuthorize("hasRole('AGENT')")
    @Transactional(readOnly = true)
    public TeamResponse get(UUID teamId) {
        return TeamResponse.from(require(teamId));
    }

    @PreAuthorize("hasRole('AGENT')")
    @Transactional(readOnly = true)
    public List<TeamMemberResponse> members(UUID teamId) {
        require(teamId);
        return members.findMembers(teamId);
    }

    // ----- administration -----------------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    public TeamResponse create(CreateTeamRequest request) {
        if (teams.existsByNameIgnoreCase(request.name().trim())) {
            throw nameAlreadyExists();
        }
        Team team = new Team(request.name(), request.description());
        try {
            teams.saveAndFlush(team);
        } catch (DataIntegrityViolationException raceLost) {
            throw nameAlreadyExists();
        }
        log.info("Team created id={} name='{}'", team.getId(), team.getName());
        return TeamResponse.from(team);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public TeamResponse update(UUID teamId, UpdateTeamRequest request) {
        Team team = require(teamId);
        StaleVersionException.check("Team", request.version(), team.getVersion());

        if (request.name() != null) {
            if (teams.existsByNameIgnoreCaseAndIdNot(request.name().trim(), teamId)) {
                throw nameAlreadyExists();
            }
            team.rename(request.name());
        }
        if (request.description() != null) {
            team.describe(request.description());
        }
        if (request.active() != null) {
            if (request.active()) {
                team.activate();
            } else {
                team.deactivate();
            }
        }
        try {
            teams.flush(); // surface constraint races here and return the incremented version
        } catch (DataIntegrityViolationException raceLost) {
            throw nameAlreadyExists();
        }
        return TeamResponse.from(team);
    }

    /** Idempotent: adding an existing member succeeds without changing anything. */
    @PreAuthorize("hasRole('ADMIN')")
    public void addMember(UUID teamId, UUID userId) {
        Team team = require(teamId);
        if (!team.isActive()) {
            throw new BusinessRuleException(ErrorCode.TEAM_INACTIVE, "Cannot add members to an inactive team");
        }
        User user = userService.require(userId);
        if (!user.isActive()) {
            throw new BusinessRuleException(
                    ErrorCode.USER_NOT_ELIGIBLE_FOR_TEAM, "Inactive users cannot be added to a team");
        }
        if (!user.getRole().isStaff()) {
            throw new BusinessRuleException(
                    ErrorCode.USER_NOT_ELIGIBLE_FOR_TEAM,
                    "Only support staff (AGENT, TEAM_LEAD, ADMIN) can be team members");
        }
        TeamMemberId id = new TeamMemberId(teamId, userId);
        if (!members.existsById(id)) {
            members.save(new TeamMember(teamId, userId, clock.instant()));
            log.info("User {} added to team {}", userId, teamId);
        }
    }

    /** Idempotent: removing a non-member succeeds. */
    @PreAuthorize("hasRole('ADMIN')")
    public void removeMember(UUID teamId, UUID userId) {
        require(teamId);
        TeamMemberId id = new TeamMemberId(teamId, userId);
        if (members.existsById(id)) {
            members.deleteById(id);
            log.info("User {} removed from team {}", userId, teamId);
        }
    }

    // ----- reactions to other modules -----------------------------------------------------------

    /**
     * A user demoted to a non-staff role can no longer be a team member. Runs synchronously inside
     * the role-change transaction, so both changes commit or roll back together.
     */
    @EventListener
    void onUserRoleChanged(UserRoleChangedEvent event) {
        if (!event.newRole().isStaff()) {
            int removed = members.deleteAllByUserId(event.userId());
            if (removed > 0) {
                log.info(
                        "Removed user {} from {} team(s) after role change to {}",
                        event.userId(),
                        removed,
                        event.newRole());
            }
        }
    }

    private Team require(UUID teamId) {
        return teams.findById(teamId).orElseThrow(() -> new ResourceNotFoundException("Team", teamId));
    }

    private static BusinessRuleException nameAlreadyExists() {
        return new BusinessRuleException(ErrorCode.TEAM_NAME_ALREADY_EXISTS, "A team with this name already exists");
    }
}
