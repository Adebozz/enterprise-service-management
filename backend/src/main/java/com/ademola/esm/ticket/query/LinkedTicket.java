package com.ademola.esm.ticket.query;

import java.util.UUID;

/** Another ticket this one is linked to, with enough to show and open it. */
public record LinkedTicket(UUID id, String reference, String title, String status) {}
