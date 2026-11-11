package com.ademola.esm.catalogue;

import com.ademola.esm.ticket.priority.Urgency;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Answers keyed by question key, plus optional free-text notes. Urgency is optional, as for any
 * service request; the answers are checked against the item's own form.
 */
public record SubmitCatalogueRequest(
        @Nullable Map<String, String> answers,
        @Nullable @Size(max = 4_000) String notes,
        @Nullable Urgency urgency) {

    Map<String, String> answersOrEmpty() {
        return answers == null ? Map.of() : answers;
    }
}
