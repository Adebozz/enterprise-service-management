package com.ademola.esm.team;

import com.ademola.esm.audit.AuditAction;
import com.ademola.esm.audit.AuditEntityType;
import com.ademola.esm.audit.AuditRecord;
import com.ademola.esm.audit.AuditService;
import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import com.ademola.esm.common.error.ResourceNotFoundException;
import com.ademola.esm.common.error.StaleVersionException;
import com.ademola.esm.user.User;
import com.ademola.esm.user.UserRoleChangedEvent;
import com.ademola.esm.user.UserService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final AuditService audit;

    public TeamService(
            TeamRepository teams,
            TeamMemberRepository members,
            UserService userService,
            Clock clock,
            AuditService audit) {
        this.teams = teams;
        this.members = members;
        this.userService = userService;
        this.clock = clock;
        this.audit = audit;
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

    /** Active teams the user belongs to. Internal API (callers pass the authenticated user's id). */
    @Transactional(readOnly = true)
    public List<TeamSummary> teamsOf(UUID userId) {
        return members.findActiveTeamsOf(userId);
    }

    /** Ids of every team (active or not) the user belongs to; used for ticket visibility. */
    @Transactional(readOnly = true)
    public Set<UUID> teamIdsOf(UUID userId) {
        return Set.copyOf(members.findTeamIdsOf(userId));
    }

    /** Names for many teams in one query (e.g. for a history timeline). */
    @Transactional(readOnly = true)
    public Map<UUID, String> namesOf(java.util.Collection<UUID> ids) {
        Map<UUID, String> names = new java.util.HashMap<>();
        teams.findAllById(ids).forEach(team -> names.put(team.getId(), team.getName()));
        return names;
    }

    /** Name lookup for display purposes (e.g. "assigned to Network Team" on a ticket). */
    @Transactional(readOnly = true)
    public TeamSummary summaryOf(UUID teamId) {
        Team team = require(teamId);
        return new TeamSummary(team.getId(), team.getName());
    }

    /** For other modules that route work to a team: it must exist and be active. */
    @Transactional(readOnly = true)
    public TeamSummary requireActiveTeam(UUID teamId) {
        Team team = require(teamId);
        if (!team.isActive()) {
            throw new BusinessRuleException(ErrorCode.TEAM_INACTIVE, "Team '%s' is inactive".formatted(team.getName()));
        }
        return new TeamSummary(team.getId(), team.getName());
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
        audit.record(AuditRecord.created(
                AuditAction.TEAM_CREATED, AuditEntityType.TEAM, team.getId(), Map.of("name", team.getName())));
        log.info("Team created id={} name='{}'", team.getId(), team.getName());
        return TeamResponse.from(team);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public TeamResponse update(UUID teamId, UpdateTeamRequest request) {
        Team team = require(teamId);
        StaleVersionException.check("Team", request.version(), team.getVersion());

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (request.name() != null && !request.name().trim().equals(team.getName())) {
            if (teams.existsByNameIgnoreCaseAndIdNot(request.name().trim(), teamId)) {
                throw nameAlreadyExists();
            }
            before.put("name", team.getName());
            team.rename(request.name());
            after.put("name", team.getName());
        }
        if (request.description() != null && !request.description().equals(team.getDescription())) {
            before.put("description", team.getDescription());
            team.describe(request.description());
            after.put("description", team.getDescription());
        }
        if (request.active() != null && request.active() != team.isActive()) {
            before.put("active", team.isActive());
            if (request.active()) {
                team.activate();
            } else {
                team.deactivate();
            }
            after.put("active", team.isActive());
        }
        if (!after.isEmpty()) {
            audit.record(AuditRecord.changed(AuditAction.TEAM_UPDATED, AuditEntityType.TEAM, teamId, before, after));
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
            audit.record(AuditRecord.event(
                    AuditAction.TEAM_MEMBER_ADDED, AuditEntityType.TEAM, teamId, Map.of("userId", userId)));
            log.info("User {} added to team {}", userId, teamId);
        }
    }

    /** Idempotent: removing a non-member succeeds. */
    @PreAuthorize("hasRole('ADMIN')")
    public void removeMember(UUID teamId, UUID userId) {
        require(teamId);
        TeamMemberId id = new TeamMemberId(teamId, userId);
        if (members.existsById(id)) {
            deleteMembership(id);
            audit.record(AuditRecord.event(
                    AuditAction.TEAM_MEMBER_REMOVED, AuditEntityType.TEAM, teamId, Map.of("userId", userId)));
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
            for (UUID teamId : members.findTeamIdsOf(event.userId())) {
                deleteMembership(new TeamMemberId(teamId, event.userId()));
                audit.record(AuditRecord.event(
                        AuditAction.TEAM_MEMBER_REMOVED,
                        AuditEntityType.TEAM,
                        teamId,
                        Map.of("userId", event.userId(), "reason", "ROLE_CHANGED_TO_" + event.newRole())));
            }
        }
    }

    /**
     * The database refuses to remove a member who still owns open tickets (trigger from V5).
     * Flushing here surfaces that refusal immediately so we can turn it into a clear 409, rather
     * than a generic failure at commit.
     */
    private void deleteMembership(TeamMemberId id) {
        try {
            members.deleteById(id);
            members.flush();
        } catch (DataIntegrityViolationException ownsOpenTickets) {
            throw new BusinessRuleException(
                    ErrorCode.MEMBER_HAS_OPEN_TICKETS,
                    "This person still owns open tickets in the team. Reassign them first.");
        }
    }

    private Team require(UUID teamId) {
        return teams.findById(teamId).orElseThrow(() -> new ResourceNotFoundException("Team", teamId));
    }

    private static BusinessRuleException nameAlreadyExists() {
        return new BusinessRuleException(ErrorCode.TEAM_NAME_ALREADY_EXISTS, "A team with this name already exists");
    }
}
