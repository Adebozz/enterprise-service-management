package com.ademola.esm.ticket.incident;

import static com.ademola.esm.ticket.incident.IncidentStatus.ASSIGNED;
import static com.ademola.esm.ticket.incident.IncidentStatus.CANCELLED;
import static com.ademola.esm.ticket.incident.IncidentStatus.CLOSED;
import static com.ademola.esm.ticket.incident.IncidentStatus.IN_PROGRESS;
import static com.ademola.esm.ticket.incident.IncidentStatus.NEW;
import static com.ademola.esm.ticket.incident.IncidentStatus.RESOLVED;
import static com.ademola.esm.ticket.incident.IncidentStatus.WAITING_FOR_USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.ticket.workflow.Actor;
import com.ademola.esm.ticket.workflow.Requirement;
import com.ademola.esm.ticket.workflow.Transition;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exhaustive check of the incident lifecycle. The allowed set is written out independently here, so
 * any change to {@link IncidentWorkflow} must be made deliberately in two places.
 */
class IncidentWorkflowTest {

    static final Set<String> ALLOWED = Set.of(
            "NEW->ASSIGNED",
            "ASSIGNED->NEW",
            "IN_PROGRESS->NEW",
            "WAITING_FOR_USER->NEW",
            "ASSIGNED->IN_PROGRESS",
            "IN_PROGRESS->WAITING_FOR_USER",
            "WAITING_FOR_USER->IN_PROGRESS",
            "IN_PROGRESS->RESOLVED",
            "RESOLVED->CLOSED",
            "RESOLVED->IN_PROGRESS",
            "NEW->CANCELLED",
            "ASSIGNED->CANCELLED",
            "IN_PROGRESS->CANCELLED",
            "WAITING_FOR_USER->CANCELLED");

    static Stream<Arguments> everyPair() {
        return Arrays.stream(IncidentStatus.values())
                .flatMap(from -> Arrays.stream(IncidentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void onlyDeclaredTransitionsExist(IncidentStatus from, IncidentStatus to) {
        boolean expected = ALLOWED.contains(from + "->" + to);

        assertThat(IncidentWorkflow.DEFINITION.find(from, to).isPresent())
                .as("%s -> %s", from, to)
                .isEqualTo(expected);
    }

    @Test
    void closedAndCancelledAreTerminal() {
        assertThat(IncidentWorkflow.DEFINITION.isTerminal(CLOSED)).isTrue();
        assertThat(IncidentWorkflow.DEFINITION.isTerminal(CANCELLED)).isTrue();
    }

    @Test
    void onlySupportCanResolveAndItNeedsAResolution() {
        Transition<IncidentStatus> resolve =
                IncidentWorkflow.DEFINITION.find(IN_PROGRESS, RESOLVED).orElseThrow();

        assertThat(resolve.allowedActors()).containsExactly(Actor.SUPPORT);
        assertThat(resolve.requirements()).containsExactly(Requirement.RESOLUTION);
    }

    @Test
    void onlyTheRequesterAdminOrSystemCanConfirmClosure() {
        Transition<IncidentStatus> close =
                IncidentWorkflow.DEFINITION.find(RESOLVED, CLOSED).orElseThrow();

        assertThat(close.allowedActors()).containsExactlyInAnyOrder(Actor.REQUESTER, Actor.ADMIN, Actor.SYSTEM);
        assertThat(close.isAllowedFor(Set.of(Actor.SUPPORT))).isFalse();
    }

    @Test
    void assignmentMovesAreReservedForTheSystem() {
        assertThat(IncidentWorkflow.DEFINITION.find(NEW, ASSIGNED).orElseThrow().allowedActors())
                .containsExactly(Actor.SYSTEM);
        assertThat(IncidentWorkflow.DEFINITION.find(ASSIGNED, NEW).orElseThrow().allowedActors())
                .containsExactly(Actor.SYSTEM);
    }

    @Test
    void returningToTheQueueIsReservedForTheSystem() {
        assertThat(IncidentWorkflow.DEFINITION
                        .find(IN_PROGRESS, NEW)
                        .orElseThrow()
                        .allowedActors())
                .containsExactly(Actor.SYSTEM);
        assertThat(IncidentWorkflow.DEFINITION
                        .find(WAITING_FOR_USER, NEW)
                        .orElseThrow()
                        .allowedActors())
                .containsExactly(Actor.SYSTEM);
    }

    @Test
    void startingWorkRequiresAnAssignee() {
        assertThat(IncidentWorkflow.DEFINITION
                        .find(ASSIGNED, IN_PROGRESS)
                        .orElseThrow()
                        .requirements())
                .containsExactly(Requirement.ASSIGNEE);
    }

    @Test
    void reopeningAndWaitingRequireAReason() {
        assertThat(IncidentWorkflow.DEFINITION
                        .find(RESOLVED, IN_PROGRESS)
                        .orElseThrow()
                        .requirements())
                .containsExactly(Requirement.REASON);
        assertThat(IncidentWorkflow.DEFINITION
                        .find(IN_PROGRESS, WAITING_FOR_USER)
                        .orElseThrow()
                        .requirements())
                .containsExactly(Requirement.REASON);
    }
}
