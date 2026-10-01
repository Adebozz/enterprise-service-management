package com.ademola.esm.ticket.request;

import com.ademola.esm.ticket.priority.Urgency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A basic service request. (Phase 2 adds catalogue-driven requests with custom fields and
 * approvals.) Urgency is optional and defaults to MEDIUM; impact starts LOW because a standard
 * request rarely affects more than the requester. Agents can reassess both.
 */
public record CreateServiceRequestRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 10_000) String description,
        @NotNull UUID categoryId,
        UUID subcategoryId,
        Urgency urgency) {}
