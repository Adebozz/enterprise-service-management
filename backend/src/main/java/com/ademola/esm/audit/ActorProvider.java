package com.ademola.esm.audit;

import java.util.Optional;
import java.util.UUID;

/**
 * Who is performing the current action. Defined here and implemented by the {@code auth} module
 * (from the authenticated principal), so {@code audit} doesn't depend on security code
 * (dependency inversion), and every module can depend on {@code audit} without creating cycles.
 */
public interface ActorProvider {

    /** Empty when the action is performed by the system (startup bootstrap, scheduled jobs). */
    Optional<UUID> currentActorId();
}
