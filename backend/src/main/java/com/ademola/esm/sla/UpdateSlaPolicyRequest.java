package com.ademola.esm.sla;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** New targets in minutes. Up to 365 days, which is far beyond any real target. */
record UpdateSlaPolicyRequest(
        @NotNull @Min(1) @Max(525_600) Integer responseMinutes,
        @NotNull @Min(1) @Max(525_600) Integer resolutionMinutes,
        @NotNull Long version) {}
