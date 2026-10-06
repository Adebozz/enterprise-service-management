package com.ademola.esm.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ademola.esm.ticket.incident.IncidentStatus;
import com.ademola.esm.ticket.incident.IncidentWorkflow;
import com.ademola.esm.ticket.request.ServiceRequestStatus;
import com.ademola.esm.ticket.request.ServiceRequestWorkflow;
import com.ademola.esm.ticket.workflow.Transition;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Transition labels are shown verbatim as buttons, including as the confirm button of a dialog
 * whose dismiss button is "Back". A label that reads like a dialog control ("Cancel", "OK") would
 * make the destructive choice look like the safe one.
 */
class TransitionLabelsTest {

    private static final Set<String> DIALOG_WORDS = Set.of("Cancel", "Back", "OK", "Close", "Confirm", "Yes", "No");

    @Test
    void labelsNeverReadLikeDialogControls() {
        assertThat(allTransitions()).extracting(Transition::label).isNotEmpty().noneMatch(DIALOG_WORDS::contains);
    }

    @Test
    void cancellingSaysWhatIsCancelled() {
        assertThat(IncidentWorkflow.DEFINITION.require("NEW", "CANCELLED").label())
                .isEqualTo("Cancel ticket");
        assertThat(ServiceRequestWorkflow.DEFINITION
                        .require("SUBMITTED", "CANCELLED")
                        .label())
                .isEqualTo("Cancel request");
    }

    @Test
    void movesFromTheSameStatusHaveDistinctLabels() {
        for (IncidentStatus status : IncidentStatus.values()) {
            assertDistinct(IncidentWorkflow.DEFINITION.transitionsFrom(status));
        }
        for (ServiceRequestStatus status : ServiceRequestStatus.values()) {
            assertDistinct(ServiceRequestWorkflow.DEFINITION.transitionsFrom(status));
        }
    }

    private static void assertDistinct(List<? extends Transition<?>> transitions) {
        assertThat(transitions).extracting(Transition::label).doesNotHaveDuplicates();
    }

    private static List<Transition<?>> allTransitions() {
        return java.util.stream.Stream.concat(
                        Arrays.stream(IncidentStatus.values())
                                .flatMap(s -> IncidentWorkflow.DEFINITION.transitionsFrom(s).stream()),
                        Arrays.stream(ServiceRequestStatus.values())
                                .flatMap(s -> ServiceRequestWorkflow.DEFINITION.transitionsFrom(s).stream()))
                .<Transition<?>>map(t -> t)
                .toList();
    }
}
