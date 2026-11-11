package com.ademola.esm.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.support.DatabaseCleaner;
import com.ademola.esm.support.Fixtures;
import com.ademola.esm.support.IntegrationTest;
import com.ademola.esm.support.TestUsers;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.user.Role;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V8: the seeded policies, the clock mapping and the database's own guarantees. */
@IntegrationTest
class SlaPersistenceIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Fixtures fixtures;

    @Autowired
    TestUsers users;

    @Autowired
    SlaPolicyService policyService;

    @Autowired
    SlaClockRepository clocks;

    UUID ticket;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        UUID team = fixtures.team("Network Team");
        UUID category = fixtures.category("NETWORK", "Network", null, team, "INCIDENT");
        UUID requester = users.create("req@example.com", Role.REQUESTER).getId();
        ticket = UUID.randomUUID();
        jdbc.update(
                "insert into work_items (id, reference, type, title, description, status, impact, urgency, priority,"
                        + " category_id, requester_id, assigned_team_id, created_at, updated_at)"
                        + " values (?, 'INC-900001', 'INCIDENT', 'T', 'D', 'NEW', 'LOW', 'LOW', 'P4', ?, ?, ?, now(), now())",
                ticket,
                category,
                requester,
                team);
    }

    @Test
    void everyTicketTypeWithServiceLevelsHasItsSeededTargets() {
        Map<WorkItemType, Map<Priority, SlaTargets>> expected = Map.of(
                WorkItemType.INCIDENT,
                        Map.of(
                                Priority.P1, targets(15, 240),
                                Priority.P2, targets(60, 480),
                                Priority.P3, targets(240, 4320),
                                Priority.P4, targets(480, 7200)),
                WorkItemType.SERVICE_REQUEST,
                        Map.of(
                                Priority.P1, targets(60, 1440),
                                Priority.P2, targets(240, 2880),
                                Priority.P3, targets(480, 7200),
                                Priority.P4, targets(1440, 14400)));

        // Problems have no SLA (M20), so they have no policies either.
        for (WorkItemType type : List.of(WorkItemType.INCIDENT, WorkItemType.SERVICE_REQUEST)) {
            for (Priority priority : Priority.values()) {
                assertThat(policyService.targetsFor(type, priority))
                        .as("%s %s", type, priority)
                        .isEqualTo(expected.get(type).get(priority));
            }
        }
    }

    @Test
    void policiesSurviveTheTestDatabaseCleaner() {
        DatabaseCleaner.clean(jdbc);

        assertThat(jdbc.queryForObject("select count(*) from sla_policies", Integer.class))
                .isEqualTo(8);
    }

    @Test
    void aMissingPolicyIsReportedAsAnInconsistentDatabase() {
        jdbc.update("delete from sla_policies where ticket_type = 'INCIDENT' and priority = 'P1'");
        try {
            assertThatThrownBy(() -> policyService.targetsFor(WorkItemType.INCIDENT, Priority.P1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("No SLA policy for INCIDENT P1");
        } finally {
            jdbc.update("insert into sla_policies (id, ticket_type, priority, response_minutes, resolution_minutes,"
                    + " created_at, updated_at) values (gen_random_uuid(), 'INCIDENT', 'P1', 15, 240, now(), now())");
        }
    }

    @Test
    void policyTargetsMustBePositiveWithResponseNoLaterThanResolution() {
        assertThatThrownBy(
                        () -> jdbc.update(
                                "update sla_policies set response_minutes = 500 where ticket_type = 'INCIDENT' and priority = 'P1'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sla_policies_targets_check");
        assertThatThrownBy(() -> jdbc.update("insert into sla_policies (id, ticket_type, priority, response_minutes,"
                        + " resolution_minutes, created_at, updated_at)"
                        + " values (gen_random_uuid(), 'INCIDENT', 'P1', 10, 20, now(), now())"))
                .hasMessageContaining("sla_policies_type_priority_uk");
    }

    @Test
    void aClockRoundTripsThroughTheDatabase() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MICROS);
        SlaClock clock = SlaClock.start(ticket, SlaMetric.RESOLUTION, Duration.ofHours(4), start);
        clock.pause(start.plus(Duration.ofMinutes(30)));
        clocks.save(clock);

        SlaClock loaded =
                clocks.findByWorkItemIdAndMetric(ticket, SlaMetric.RESOLUTION).orElseThrow();

        assertThat(loaded.getState()).isEqualTo(SlaClockState.PAUSED);
        assertThat(loaded.target()).isEqualTo(Duration.ofHours(4));
        assertThat(loaded.elapsed(start.plus(Duration.ofDays(1)))).isEqualTo(Duration.ofMinutes(30));
        assertThat(loaded.getDueAt()).isNull();
        assertThat(clocks.findByWorkItemIdAndMetric(ticket, SlaMetric.RESPONSE)).isEmpty();
    }

    @Test
    void aTicketHasAtMostOneClockPerMetric() {
        insertClock(SlaMetric.RESPONSE);

        assertThatThrownBy(() -> insertClock(SlaMetric.RESPONSE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sla_clocks_work_item_metric_uk");
        insertClock(SlaMetric.RESOLUTION);
    }

    @Test
    void onlyARunningClockHasACurrentStretchAndADeadline() {
        assertThatThrownBy(() -> jdbc.update(
                        "insert into sla_clocks (id, work_item_id, metric, target_minutes, state,"
                                + " running_since, created_at, updated_at)"
                                + " values (gen_random_uuid(), ?, 'RESPONSE', 15, 'RUNNING', now(), now(), now())",
                        ticket))
                .hasMessageContaining("sla_clocks_running_check");
        assertThatThrownBy(() -> jdbc.update(
                        "insert into sla_clocks (id, work_item_id, metric, target_minutes, state,"
                                + " due_at, created_at, updated_at)"
                                + " values (gen_random_uuid(), ?, 'RESPONSE', 15, 'PAUSED', now(), now(), now())",
                        ticket))
                .hasMessageContaining("sla_clocks_running_check");
    }

    @Test
    void aClockIsCompletedExactlyWhenItHasACompletionTime() {
        assertThatThrownBy(() -> jdbc.update(
                        "insert into sla_clocks (id, work_item_id, metric, target_minutes, state,"
                                + " created_at, updated_at) values (gen_random_uuid(), ?, 'RESPONSE', 15, 'COMPLETED', now(), now())",
                        ticket))
                .hasMessageContaining("sla_clocks_completed_check");
    }

    @Test
    void deletingATicketDeletesItsClocks() {
        insertClock(SlaMetric.RESPONSE);

        jdbc.update("delete from work_items where id = ?", ticket);

        assertThat(jdbc.queryForObject("select count(*) from sla_clocks", Integer.class))
                .isZero();
    }

    private void insertClock(SlaMetric metric) {
        jdbc.update(
                "insert into sla_clocks (id, work_item_id, metric, target_minutes, state, running_since, due_at,"
                        + " created_at, updated_at) values (gen_random_uuid(), ?, ?, 15, 'RUNNING', now(),"
                        + " now() + interval '15 minutes', now(), now())",
                ticket,
                metric.name());
    }

    private static SlaTargets targets(int responseMinutes, int resolutionMinutes) {
        return new SlaTargets(Duration.ofMinutes(responseMinutes), Duration.ofMinutes(resolutionMinutes));
    }
}
