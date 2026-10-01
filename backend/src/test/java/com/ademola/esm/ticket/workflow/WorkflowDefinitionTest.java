package com.ademola.esm.ticket.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ademola.esm.common.error.BusinessRuleException;
import com.ademola.esm.common.error.ErrorCode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionTest {

    enum Light {
        RED,
        GREEN,
        AMBER
    }

    final WorkflowDefinition<Light> lights = WorkflowDefinition.builder("Light", Light.class)
            .allow(Light.RED, Light.GREEN, "Go", Set.of(Actor.SYSTEM))
            .allow(Light.GREEN, Light.AMBER, "Slow", Set.of(Actor.SYSTEM), Set.of(Requirement.REASON))
            .build();

    @Test
    void undeclaredMoveIsRejectedWithAClearMessage() {
        assertThatThrownBy(() -> lights.require("RED", "AMBER"))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessage("Light cannot transition from RED to AMBER");
    }

    @Test
    void unknownStatusIsRejectedRatherThanCrashing() {
        assertThatThrownBy(() -> lights.require("RED", "BLUE"))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessage("'BLUE' is not a valid light status");
        assertThatThrownBy(() -> lights.require("RED", null)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void duplicateDeclarationsAreAProgrammingError() {
        var builder = WorkflowDefinition.builder("Light", Light.class)
                .allow(Light.RED, Light.GREEN, "Go", Set.of(Actor.SYSTEM));

        assertThatThrownBy(() -> builder.allow(Light.RED, Light.GREEN, "Again", Set.of(Actor.SYSTEM)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void selfTransitionsAndActorlessTransitionsAreRejected() {
        var builder = WorkflowDefinition.builder("Light", Light.class);

        assertThatThrownBy(() -> builder.allow(Light.RED, Light.RED, "Stay", Set.of(Actor.SYSTEM)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.allow(Light.RED, Light.GREEN, "Nobody", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requirementsAreCheckedAgainstInputAndTicketState() {
        Transition<Light> slow = lights.find(Light.GREEN, Light.AMBER).orElseThrow();

        assertThatThrownBy(() -> slow.checkRequirements(new TransitionInput("  ", null, null), true))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code())
                .isEqualTo(ErrorCode.TRANSITION_REQUIREMENT_MISSING);
        slow.checkRequirements(new TransitionInput("traffic", null, null), true); // no exception
    }

    @Test
    void actorCheckIsAnyOf() {
        Transition<Light> t =
                new Transition<>(Light.RED, Light.GREEN, "Go", Set.of(Actor.REQUESTER, Actor.SUPPORT), Set.of());

        assertThat(t.isAllowedFor(Set.of(Actor.SUPPORT))).isTrue();
        assertThat(t.isAllowedFor(Set.of(Actor.ADMIN))).isFalse();
        assertThat(t.isAllowedFor(Set.of())).isFalse();
    }
}
