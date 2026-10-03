package com.ademola.esm.ticket.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.auth.CurrentUser;
import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.priority.Priority;
import com.ademola.esm.user.Role;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** The generated SQL: visibility rules, views, filters, search and, above all, injection safety. */
class TicketListQueryTest {

    static final TicketSearchParams NONE =
            new TicketSearchParams(null, null, null, null, null, null, null, null, null, null, null);

    final UUID team = UUID.randomUUID();
    final CurrentUser requester = new CurrentUser(UUID.randomUUID(), Role.REQUESTER, "R");
    final CurrentUser agent = new CurrentUser(UUID.randomUUID(), Role.AGENT, "A");
    final CurrentUser admin = new CurrentUser(UUID.randomUUID(), Role.ADMIN, "Ad");

    @Test
    void requesterIsLimitedToTheirOwnTickets() {
        String sql = sql(requester, Set.of(), NONE);

        assertThat(sql).contains("WHERE w.requester_id = :me").doesNotContain("myTeams");
    }

    @Test
    void staffSeeOwnAssignedAndTeamTickets() {
        assertThat(sql(agent, Set.of(team), NONE))
                .contains("(w.requester_id = :me OR w.assignee_id = :me OR w.assigned_team_id IN (:myTeams))");
        assertThat(sql(agent, Set.of(), NONE)).contains("(w.requester_id = :me OR w.assignee_id = :me)");
    }

    @Test
    void adminQueryHasNoVisibilityRestriction() {
        assertThat(sql(admin, Set.of(), NONE)).doesNotContain("WHERE");
    }

    @Test
    void unassignedViewUsesLiteralTerminalStatusesSoThePartialIndexApplies() {
        String sql = sql(agent, Set.of(team), withView(TicketView.UNASSIGNED));

        assertThat(sql)
                .contains("w.assignee_id IS NULL")
                .contains("w.status NOT IN ('CLOSED', 'CANCELLED', 'REJECTED')")
                .contains("w.assigned_team_id IN (:myTeams)");
        assertThat(sql(admin, Set.of(), withView(TicketView.UNASSIGNED))).doesNotContain("myTeams");
    }

    @Test
    void teamViewForStaffWithoutTeamsMatchesNothing() {
        assertThat(sql(agent, Set.of(), withView(TicketView.TEAM))).contains("AND false");
    }

    @Test
    void filtersBecomeBindParametersAndDatesAreInclusiveDays() {
        TicketSearchParams p = new TicketSearchParams(
                null,
                List.of(WorkItemType.INCIDENT),
                List.of("NEW", "ASSIGNED"),
                List.of(Priority.P1),
                team,
                null,
                null,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 2),
                true,
                null);

        TicketListQuery.Sql sql = TicketListQuery.build(admin, Set.of(), p, TicketListQuery.NameMatches.NONE)
                .page(PageRequest.of(0, 20));

        assertThat(sql.text())
                .contains("w.type IN (:types)", "w.status IN (:statuses)", "w.priority IN (:priorities)")
                .contains("w.created_at >= :createdFrom", "w.created_at < :createdToExclusive");
        assertThat(sql.params().getValue("statuses")).isEqualTo(List.of("NEW", "ASSIGNED"));
        assertThat(sql.params().getValue("createdToExclusive"))
                .isEqualTo(OffsetDateTime.of(2026, 10, 3, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @CsvSource({"inc-42, INC-000042", "INC-000042, INC-000042", "req-7, REQ-000007", " INC-1234567 , INC-1234567"})
    void referencesAreNormalised(String input, String expected) {
        assertThat(TicketListQuery.asReference(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"printer", "INC-", "INC-12a", "INC-1 OR 1=1", "XYZ-000001"})
    void nonReferencesAreNotTreatedAsReferences(String input) {
        assertThat(TicketListQuery.asReference(input)).isNull();
    }

    @Test
    void referenceSearchIsAnExactIndexedMatch() {
        TicketListQuery.Sql sql = TicketListQuery.build(
                        admin, Set.of(), withQuery("inc-5"), TicketListQuery.NameMatches.NONE)
                .page(PageRequest.of(0, 20));

        assertThat(sql.text()).contains("w.reference = :reference").doesNotContain("search_vector @@");
        assertThat(sql.params().getValue("reference")).isEqualTo("INC-000005");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "'; DROP TABLE users; --",
                "x') OR 1=1 --",
                "\" OR \"\"=\"",
                "printer; SELECT * FROM refresh_tokens"
            })
    void searchTextIsNeverPartOfTheSql(String payload) {
        TicketListQuery query = TicketListQuery.build(
                agent,
                Set.of(team),
                withQuery(payload),
                new TicketListQuery.NameMatches(List.of(UUID.randomUUID()), List.of()));
        TicketListQuery.Sql page = query.page(PageRequest.of(0, 20));

        assertThat(page.text()).doesNotContain(payload).doesNotContain("DROP").doesNotContain("refresh_tokens");
        assertThat(query.count().text()).doesNotContain(payload);
        assertThat(page.params().getValue("q")).isEqualTo(payload.strip());
    }

    @Test
    void searchOrdersByRelevanceUnlessASortIsGiven() {
        String byRelevance = TicketListQuery.build(
                        admin, Set.of(), withQuery("printer"), TicketListQuery.NameMatches.NONE)
                .page(PageRequest.of(0, 20))
                .text();
        String byPriority = TicketListQuery.build(
                        admin, Set.of(), withQuery("printer"), TicketListQuery.NameMatches.NONE)
                .page(PageRequest.of(0, 20, Sort.by("priority")))
                .text();

        assertThat(byRelevance).contains("ORDER BY ts_rank(");
        assertThat(byPriority).contains("ORDER BY w.priority ASC, w.id").doesNotContain("ts_rank");
    }

    @Test
    void everyOrderEndsWithTheIdTieBreakerForStablePages() {
        assertThat(sql(admin, Set.of(), NONE))
                .endsWith("ORDER BY w.created_at DESC, w.id\nLIMIT :limit OFFSET :offset");
    }

    @Test
    void unknownSortPropertyCanNeverReachTheSql() {
        TicketListQuery query = TicketListQuery.build(admin, Set.of(), NONE, TicketListQuery.NameMatches.NONE);

        assertThatThrownBy(() -> query.page(PageRequest.of(0, 20, Sort.by("w.id; DROP TABLE users"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String sql(CurrentUser user, Set<UUID> teams, TicketSearchParams params) {
        return TicketListQuery.build(user, teams, params, TicketListQuery.NameMatches.NONE)
                .page(PageRequest.of(0, 20))
                .text();
    }

    private static TicketSearchParams withView(TicketView view) {
        return new TicketSearchParams(view, null, null, null, null, null, null, null, null, null, null);
    }

    private static TicketSearchParams withQuery(String q) {
        return new TicketSearchParams(null, null, null, null, null, null, null, null, null, null, q);
    }
}
