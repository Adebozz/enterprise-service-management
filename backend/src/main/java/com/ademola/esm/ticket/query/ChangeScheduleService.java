package com.ademola.esm.ticket.query;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.team.TeamService;
import com.ademola.esm.ticket.change.Change;
import com.ademola.esm.ticket.change.ChangeRepository;
import com.ademola.esm.user.UserService;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The forward schedule of change: what is planned to happen to live services, and when. Every
 * member of staff sees all of it, so teams can avoid working on the same thing at the same time.
 */
@Service
@Transactional(readOnly = true)
public class ChangeScheduleService {

    private final ChangeRepository changes;
    private final TeamService teams;
    private final UserService users;
    private final Clock clock;

    ChangeScheduleService(ChangeRepository changes, TeamService teams, UserService users, Clock clock) {
        this.changes = changes;
        this.teams = teams;
        this.users = users;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('AGENT')")
    public List<ScheduledChange> upcoming(CurrentUser user) {
        List<Change> scheduled = changes.findScheduledEndingAfter(clock.instant(), ChangeRepository.SCHEDULED);
        Set<UUID> teamIds = new HashSet<>();
        Set<UUID> people = new HashSet<>();
        scheduled.forEach(change -> {
            teamIds.add(change.getAssignedTeamId());
            if (change.getAssigneeId() != null) {
                people.add(change.getAssigneeId());
            }
        });
        Map<UUID, String> teamNames = teams.namesOf(teamIds);
        Map<UUID, String> names = users.displayNamesOf(people);
        return scheduled.stream()
                .map(change -> new ScheduledChange(
                        change.getId(),
                        change.getReference(),
                        change.getTitle(),
                        change.getStatusName(),
                        change.getChangeType(),
                        change.getRisk(),
                        change.getPlannedStart(),
                        change.getPlannedEnd(),
                        new NamedRef(change.getAssignedTeamId(), teamNames.get(change.getAssignedTeamId())),
                        change.getAssigneeId() == null
                                ? null
                                : new NamedRef(change.getAssigneeId(), names.get(change.getAssigneeId()))))
                .toList();
    }
}
