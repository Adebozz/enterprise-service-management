package com.ademola.esm.ticket.priority;

import org.springframework.stereotype.Component;

/**
 * The single place that decides a ticket's priority. Nothing else in the codebase may derive
 * priority from impact/urgency, so changing the policy means changing configuration, not code.
 *
 * <pre>
 *                 Urgency HIGH   MEDIUM   LOW
 *   Impact HIGH          P1      P2       P3
 *          MEDIUM        P2      P3       P4
 *          LOW           P3      P4       P4      (default; see application.yml)
 * </pre>
 */
@Component
public class PriorityPolicy {

    private final PriorityMatrixProperties properties;

    public PriorityPolicy(PriorityMatrixProperties properties) {
        this.properties = properties;
    }

    public Priority calculate(Impact impact, Urgency urgency) {
        return properties.matrix().get(impact).get(urgency);
    }
}
