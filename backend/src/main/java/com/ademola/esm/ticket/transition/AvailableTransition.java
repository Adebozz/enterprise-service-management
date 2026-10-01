package com.ademola.esm.ticket.transition;

import com.ademola.esm.ticket.workflow.Requirement;
import java.util.Set;

/** A move the caller can make right now; the UI renders one button per entry. */
public record AvailableTransition(String targetStatus, String label, Set<Requirement> requirements) {}
