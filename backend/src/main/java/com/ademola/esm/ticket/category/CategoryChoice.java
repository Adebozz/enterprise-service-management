package com.ademola.esm.ticket.category;

import java.util.UUID;

/** A validated category (+ optional subcategory) for a new ticket, with the team it routes to. */
public record CategoryChoice(UUID categoryId, UUID subcategoryId, UUID routedTeamId) {}
