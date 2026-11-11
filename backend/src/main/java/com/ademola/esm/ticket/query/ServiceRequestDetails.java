package com.ademola.esm.ticket.query;

import com.ademola.esm.ticket.request.RequestAnswer;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** {@code answers} is empty unless the request was ordered from the catalogue. */
public record ServiceRequestDetails(
        @Nullable UUID catalogueItemId,
        List<RequestAnswer> answers,
        @Nullable String fulfilmentNotes) {}
