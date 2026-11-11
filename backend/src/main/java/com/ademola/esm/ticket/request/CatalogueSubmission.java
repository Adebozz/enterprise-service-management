package com.ademola.esm.ticket.request;

import com.ademola.esm.ticket.priority.Urgency;
import java.util.List;
import java.util.UUID;

/**
 * A service request ordered from the catalogue, already validated against the item's form by the
 * catalogue module. The ticket side only records it: it never needs to know what a catalogue item
 * is, which keeps the dependency one-way (catalogue -> ticket).
 */
public record CatalogueSubmission(
        UUID catalogueItemId,
        String title,
        String description,
        UUID categoryId,
        UUID subcategoryId,
        Urgency urgency,
        List<RequestAnswer> answers) {}
