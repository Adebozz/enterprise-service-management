package com.ademola.esm.ticket.priority;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The impact x urgency -> priority matrix from {@code esm.priority.matrix}.
 *
 * <p>Validated on startup: a missing cell would otherwise surface as a {@code NullPointerException}
 * the first time someone raised a ticket with that combination.
 */
@ConfigurationProperties("esm.priority")
public record PriorityMatrixProperties(Map<Impact, Map<Urgency, Priority>> matrix) {

    public PriorityMatrixProperties {
        if (matrix == null) {
            throw new IllegalStateException("esm.priority.matrix must be configured");
        }
        Map<Impact, Map<Urgency, Priority>> copy = new EnumMap<>(Impact.class);
        for (Impact impact : Impact.values()) {
            Map<Urgency, Priority> row = matrix.get(impact);
            for (Urgency urgency : Urgency.values()) {
                if (row == null || row.get(urgency) == null) {
                    throw new IllegalStateException(
                            "esm.priority.matrix is missing impact=%s, urgency=%s".formatted(impact, urgency));
                }
            }
            copy.put(impact, Map.copyOf(row));
        }
        matrix = Map.copyOf(copy);
    }
}
