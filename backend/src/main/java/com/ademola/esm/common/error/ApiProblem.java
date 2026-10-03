package com.ademola.esm.common.error;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;

/**
 * Documentation-only description of the error body that {@link GlobalExceptionHandler} produces
 * (RFC 9457 {@code application/problem+json} plus our extensions). It is published in the OpenAPI
 * contract so clients get a typed error. {@code ApiContractIT} checks real error responses
 * against it.
 */
@Schema(name = "ApiProblem", description = "RFC 9457 problem details, returned for every error")
public record ApiProblem(
        @Schema(description = "Problem type URI", example = "about:blank")
        String type,

        @Schema(example = "Conflict") String title,
        @Schema(example = "409") int status,

        @Nullable @Schema(description = "Human-readable explanation; never contains internal details")
        String detail,

        @Nullable
        @Schema(description = "Request path", example = "/api/tickets/0199b2c4-6a1e-7c3e-9f00-1a2b3c4d5e6f/transitions")
        String instance,

        @Schema(description = "Stable machine-readable code: branch on this, never on detail")
        ErrorCode code,

        Instant timestamp,

        @Nullable @Schema(description = "Matches the X-Request-Id response header and server log lines")
        String correlationId,

        @Nullable @Schema(description = "Present for VALIDATION_FAILED")
        List<GlobalExceptionHandler.FieldErrorDetail> fieldErrors) {}
