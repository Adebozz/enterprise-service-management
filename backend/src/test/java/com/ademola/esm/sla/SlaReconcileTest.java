package com.ademola.esm.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.ticket.WorkItemType;
import com.ademola.esm.ticket.incident.IncidentStatus;
import com.ademola.esm.ticket.request.ServiceRequestStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** How ticket states map to clock operations, without Spring or a database. */
class SlaReconcileTest {

    static final Instant T0 = Instant.parse("2026-11-11T09:00:00Z");

    UUID ticket = UUID.randomUUID();
    SlaClock response = SlaClock.start(ticket, SlaMetric.RESPONSE, Duration.ofHours(1), T0);
    SlaClock resolution = SlaClock.start(ticket, SlaMetric.RESOLUTION, Duration.ofHours(8), T0);

    void reconcile(SlaPhase phase, boolean responded, Duration sinceStart) {
        Instant now = T0.plus(sinceStart);
        SlaClockService.reconcile(response, phase, responded, now);
        SlaClockService.reconcile(resolution, phase, responded, now);
    }

    @Test
    void theFirstResponseStopsOnlyTheResponseClock() {
        reconcile(SlaPhase.ACTIVE, true, Duration.ofMinutes(20));

        assertThat(response.getState()).isEqualTo(SlaClockState.COMPLETED);
        assertThat(response.getCompletedAt()).isEqualTo(T0.plus(Duration.ofMinutes(20)));
        assertThat(resolution.getState()).isEqualTo(SlaClockState.RUNNING);
    }

    @Test
    void waitingForTheRequesterPausesAndActiveWorkResumes() {
        reconcile(SlaPhase.ACTIVE, true, Duration.ofMinutes(20));
        reconcile(SlaPhase.WAITING, true, Duration.ofHours(1));

        assertThat(resolution.getState()).isEqualTo(SlaClockState.PAUSED);
        assertThat(response.getState()).isEqualTo(SlaClockState.COMPLETED);

        reconcile(SlaPhase.ACTIVE, true, Duration.ofHours(30));

        assertThat(resolution.getState()).isEqualTo(SlaClockState.RUNNING);
        assertThat(resolution.elapsed(T0.plus(Duration.ofHours(30)))).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void doneStopsTheResolutionClockAndReopeningRestartsOnlyIt() {
        reconcile(SlaPhase.ACTIVE, true, Duration.ofMinutes(20));
        reconcile(SlaPhase.DONE, true, Duration.ofHours(2));

        assertThat(resolution.getState()).isEqualTo(SlaClockState.COMPLETED);

        reconcile(SlaPhase.ACTIVE, true, Duration.ofHours(5));

        assertThat(resolution.getState()).isEqualTo(SlaClockState.RUNNING);
        assertThat(resolution.elapsed(T0.plus(Duration.ofHours(5)))).isEqualTo(Duration.ofHours(2));
        assertThat(response.getState()).isEqualTo(SlaClockState.COMPLETED);
    }

    @Test
    void closingAResolvedTicketChangesNothing() {
        reconcile(SlaPhase.ACTIVE, true, Duration.ofMinutes(20));
        reconcile(SlaPhase.DONE, true, Duration.ofHours(2));

        reconcile(SlaPhase.DONE, true, Duration.ofHours(9));

        assertThat(resolution.getCompletedAt()).isEqualTo(T0.plus(Duration.ofHours(2)));
    }

    @Test
    void cancellingStopsOpenClocksButKeepsAMetResponse() {
        reconcile(SlaPhase.ACTIVE, true, Duration.ofMinutes(20));
        reconcile(SlaPhase.CANCELLED, true, Duration.ofHours(1));

        assertThat(response.getState()).isEqualTo(SlaClockState.COMPLETED);
        assertThat(resolution.getState()).isEqualTo(SlaClockState.CANCELLED);

        reconcile(SlaPhase.CANCELLED, true, Duration.ofHours(2)); // repeated: nothing left to do
        assertThat(resolution.getState()).isEqualTo(SlaClockState.CANCELLED);
    }

    @Test
    void cancellingBeforeAnyResponseCancelsBothClocks() {
        reconcile(SlaPhase.CANCELLED, false, Duration.ofMinutes(5));

        assertThat(response.getState()).isEqualTo(SlaClockState.CANCELLED);
        assertThat(resolution.getState()).isEqualTo(SlaClockState.CANCELLED);
    }

    @Test
    void reconcilingTheSameStateTwiceIsHarmless() {
        reconcile(SlaPhase.ACTIVE, false, Duration.ofMinutes(5));
        reconcile(SlaPhase.ACTIVE, false, Duration.ofMinutes(6));
        reconcile(SlaPhase.WAITING, false, Duration.ofMinutes(7));
        reconcile(SlaPhase.WAITING, false, Duration.ofMinutes(8));

        assertThat(response.getState()).isEqualTo(SlaClockState.PAUSED);
        assertThat(resolution.getState()).isEqualTo(SlaClockState.PAUSED);
    }

    @Test
    void everyIncidentStatusHasAPhase() {
        Map<String, SlaPhase> phases = phasesOf(WorkItemType.INCIDENT, IncidentStatus.values(), IncidentStatus::name);

        assertThat(phases)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "NEW", SlaPhase.ACTIVE,
                        "ASSIGNED", SlaPhase.ACTIVE,
                        "IN_PROGRESS", SlaPhase.ACTIVE,
                        "WAITING_FOR_USER", SlaPhase.WAITING,
                        "RESOLVED", SlaPhase.DONE,
                        "CLOSED", SlaPhase.DONE,
                        "CANCELLED", SlaPhase.CANCELLED));
    }

    @Test
    void everyServiceRequestStatusHasAPhase() {
        Map<String, SlaPhase> phases =
                phasesOf(WorkItemType.SERVICE_REQUEST, ServiceRequestStatus.values(), ServiceRequestStatus::name);

        assertThat(phases)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "SUBMITTED", SlaPhase.ACTIVE,
                        "APPROVAL_PENDING", SlaPhase.WAITING,
                        "APPROVED", SlaPhase.ACTIVE,
                        "REJECTED", SlaPhase.CANCELLED,
                        "IN_PROGRESS", SlaPhase.ACTIVE,
                        "FULFILLED", SlaPhase.DONE,
                        "CLOSED", SlaPhase.DONE,
                        "CANCELLED", SlaPhase.CANCELLED));
    }

    @Test
    void problemsAndChangesHaveNoPhase() {
        assertThatThrownBy(() -> SlaPhase.of(WorkItemType.PROBLEM, "NEW")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlaPhase.of(WorkItemType.CHANGE, "NEW")).isInstanceOf(IllegalArgumentException.class);
    }

    private static <S> Map<String, SlaPhase> phasesOf(WorkItemType type, S[] statuses, Function<S, String> name) {
        return Arrays.stream(statuses)
                .map(name)
                .collect(Collectors.toMap(status -> status, status -> SlaPhase.of(type, status)));
    }
}
