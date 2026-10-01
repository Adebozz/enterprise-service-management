package com.ademola.esm.ticket.query;

public record IncidentDetails(String affectedService, String resolutionCode, String resolutionNotes, int reopenCount) {}
