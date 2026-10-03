package com.ademola.esm.ticket.query;

import jakarta.annotation.Nullable;
import java.util.UUID;

public record ServiceRequestDetails(
        @Nullable UUID catalogueItemId, @Nullable String fulfilmentNotes) {}
