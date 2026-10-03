package com.ademola.esm.ticket.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A lifecycle as data: the complete set of allowed status changes for one ticket type.
 *
 * <p>Anything not declared here is impossible. That makes the rules easy to read, review and test
 * exhaustively (every from/to pair), compared with status checks scattered across services.
 */
public final class WorkflowDefinition<S extends Enum<S>> {

    private final String subject;
    private final Class<S> statusType;
    private final Map<S, Map<S, Transition<S>>> transitions;

    private WorkflowDefinition(String subject, Class<S> statusType, Map<S, Map<S, Transition<S>>> transitions) {
        this.subject = subject;
        this.statusType = statusType;
        this.transitions = transitions;
    }

    public static <S extends Enum<S>> Builder<S> builder(String subject, Class<S> statusType) {
        return new Builder<>(subject, statusType);
    }

    public Optional<Transition<S>> find(S from, S to) {
        return Optional.ofNullable(transitions.getOrDefault(from, Map.of()).get(to));
    }

    public List<Transition<S>> transitionsFrom(S from) {
        return List.copyOf(transitions.getOrDefault(from, Map.of()).values());
    }

    public boolean isTerminal(S status) {
        return transitionsFrom(status).isEmpty();
    }

    /** Name-based variant for code that handles tickets of any type. */
    public boolean isTerminal(String status) {
        return isTerminal(parse(status));
    }

    /** Name-based lookup for code that handles tickets of any type. */
    public Transition<S> require(String from, String to) {
        S fromStatus = parse(from);
        S toStatus = parse(to);
        return find(fromStatus, toStatus)
                .orElseThrow(() -> new InvalidTransitionException(
                        "%s cannot transition from %s to %s".formatted(subject, fromStatus, toStatus)));
    }

    public List<Transition<S>> transitionsFrom(String from) {
        return transitionsFrom(parse(from));
    }

    public S parse(String status) {
        try {
            return Enum.valueOf(statusType, status);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidTransitionException(
                    "'%s' is not a valid %s status".formatted(status, subject.toLowerCase()));
        }
    }

    public String subject() {
        return subject;
    }

    public static final class Builder<S extends Enum<S>> {

        private final String subject;
        private final Class<S> statusType;
        private final Map<S, Map<S, Transition<S>>> transitions;

        private Builder(String subject, Class<S> statusType) {
            this.subject = subject;
            this.statusType = statusType;
            this.transitions = new EnumMap<>(statusType);
        }

        public Builder<S> allow(S from, S to, String label, Set<Actor> actors, Set<Requirement> requirements) {
            if (from == to) {
                throw new IllegalArgumentException(
                        "Self-transition %s -> %s is not a status change".formatted(from, to));
            }
            Transition<S> previous = transitions
                    .computeIfAbsent(from, k -> new EnumMap<>(statusType))
                    .putIfAbsent(to, new Transition<>(from, to, label, actors, requirements));
            if (previous != null) {
                throw new IllegalStateException("Duplicate transition %s -> %s".formatted(from, to));
            }
            return this;
        }

        public Builder<S> allow(S from, S to, String label, Set<Actor> actors) {
            return allow(from, to, label, actors, Set.of());
        }

        /** The same move from several states (typically "Cancel"). */
        public Builder<S> allowFromEach(
                List<S> froms, S to, String label, Set<Actor> actors, Set<Requirement> requirements) {
            for (S from : new ArrayList<>(froms)) {
                allow(from, to, label, actors, requirements);
            }
            return this;
        }

        public WorkflowDefinition<S> build() {
            Map<S, Map<S, Transition<S>>> frozen = new EnumMap<>(statusType);
            // EnumMap keeps a deterministic order (enum declaration order), e.g. for UI buttons.
            transitions.forEach(
                    (from, targets) -> frozen.put(from, Collections.unmodifiableMap(new EnumMap<>(targets))));
            return new WorkflowDefinition<>(subject, statusType, frozen);
        }
    }
}
