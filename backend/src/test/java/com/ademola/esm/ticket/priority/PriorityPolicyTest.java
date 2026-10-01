package com.ademola.esm.ticket.priority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriorityPolicyTest {

    /** Same matrix as application.yml. If someone edits one, this test makes the change visible. */
    static Map<Impact, Map<Urgency, Priority>> defaultMatrix() {
        return Map.of(
                Impact.HIGH, Map.of(Urgency.HIGH, Priority.P1, Urgency.MEDIUM, Priority.P2, Urgency.LOW, Priority.P3),
                Impact.MEDIUM, Map.of(Urgency.HIGH, Priority.P2, Urgency.MEDIUM, Priority.P3, Urgency.LOW, Priority.P4),
                Impact.LOW, Map.of(Urgency.HIGH, Priority.P3, Urgency.MEDIUM, Priority.P4, Urgency.LOW, Priority.P4));
    }

    private final PriorityPolicy policy = new PriorityPolicy(new PriorityMatrixProperties(defaultMatrix()));

    @ParameterizedTest(name = "impact {0} + urgency {1} = {2}")
    @CsvSource({
        "HIGH,   HIGH,   P1",
        "HIGH,   MEDIUM, P2",
        "HIGH,   LOW,    P3",
        "MEDIUM, HIGH,   P2",
        "MEDIUM, MEDIUM, P3",
        "MEDIUM, LOW,    P4",
        "LOW,    HIGH,   P3",
        "LOW,    MEDIUM, P4",
        "LOW,    LOW,    P4"
    })
    void everyCellOfTheMatrix(Impact impact, Urgency urgency, Priority expected) {
        assertThat(policy.calculate(impact, urgency)).isEqualTo(expected);
    }

    @Test
    void incompleteMatrixIsRejectedAtStartupNamingTheMissingCell() {
        Map<Impact, Map<Urgency, Priority>> incomplete = new HashMap<>(defaultMatrix());
        incomplete.put(Impact.LOW, Map.of(Urgency.HIGH, Priority.P3, Urgency.MEDIUM, Priority.P4));

        assertThatThrownBy(() -> new PriorityMatrixProperties(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("impact=LOW, urgency=LOW");
    }

    @Test
    void missingRowIsRejected() {
        Map<Impact, Map<Urgency, Priority>> incomplete = new HashMap<>(defaultMatrix());
        incomplete.remove(Impact.MEDIUM);

        assertThatThrownBy(() -> new PriorityMatrixProperties(incomplete)).isInstanceOf(IllegalStateException.class);
    }
}
