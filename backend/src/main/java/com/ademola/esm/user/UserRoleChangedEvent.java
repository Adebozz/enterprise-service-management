package com.ademola.esm.user;

import java.util.UUID;

/**
 * Published when an admin changes a user's role. Other modules react without the {@code user}
 * module depending on them. For example, {@code team} removes memberships of users demoted to
 * REQUESTER.
 *
 * <p>Listeners are synchronous and run inside the publishing transaction, so if a listener fails,
 * the role change is rolled back too.
 */
public record UserRoleChangedEvent(UUID userId, Role previousRole, Role newRole) {}
