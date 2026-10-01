package com.ademola.esm.ticket.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.ticket.workflow.Actor;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ServiceRequestWorkflowTest {

    static final Set<String> ALLOWED = Set.of(
            "SUBMITTED->IN_PROGRESS",
            "SUBMITTED->APPROVAL_PENDING",
            "APPROVAL_PENDING->APPROVED",
            "APPROVAL_PENDING->REJECTED",
            "APPROVED->IN_PROGRESS",
            "IN_PROGRESS->FULFILLED",
            "FULFILLED->CLOSED",
            "FULFILLED->IN_PROGRESS",
            "SUBMITTED->CANCELLED",
            "APPROVAL_PENDING->CANCELLED",
            "APPROVED->CANCELLED",
            "IN_PROGRESS->CANCELLED");

    static Stream<Arguments> everyPair() {
        return Arrays.stream(ServiceRequestStatus.values())
                .flatMap(from -> Arrays.stream(ServiceRequestStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void onlyDeclaredTransitionsExist(ServiceRequestStatus from, ServiceRequestStatus to) {
        assertThat(ServiceRequestWorkflow.DEFINITION.find(from, to).isPresent())
                .as("%s -> %s", from, to)
                .isEqualTo(ALLOWED.contains(from + "->" + to));
    }

    @Test
    void approvalDecisionsCanOnlyBeMadeByTheApprovalEngine() {
        for (ServiceRequestStatus decision :
                new ServiceRequestStatus[] {ServiceRequestStatus.APPROVED, ServiceRequestStatus.REJECTED}) {
            assertThat(ServiceRequestWorkflow.DEFINITION
                            .find(ServiceRequestStatus.APPROVAL_PENDING, decision)
                            .orElseThrow()
                            .allowedActors())
                    .containsExactly(Actor.SYSTEM);
        }
    }

    @Test
    void terminalStatesHaveNoWayOut() {
        assertThat(ServiceRequestWorkflow.DEFINITION.isTerminal(ServiceRequestStatus.CLOSED))
                .isTrue();
        assertThat(ServiceRequestWorkflow.DEFINITION.isTerminal(ServiceRequestStatus.CANCELLED))
                .isTrue();
        assertThat(ServiceRequestWorkflow.DEFINITION.isTerminal(ServiceRequestStatus.REJECTED))
                .isTrue();
    }
}
