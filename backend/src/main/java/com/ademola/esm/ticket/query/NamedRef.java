package com.ademola.esm.ticket.query;

import java.util.UUID;

/** An id plus a display name, so clients don't need extra calls to render "Network Team". */
public record NamedRef(UUID id, String name) {}
