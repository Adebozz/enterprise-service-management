package com.ademola.esm.sla;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The whole working week: one entry per open day (a day left out is closed). */
record UpdateBusinessWeekRequest(
        @NotNull @Size(min = 1, max = 7) @Valid List<BusinessHoursResponse.OpeningDay> days) {}
