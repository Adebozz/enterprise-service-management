package com.ademola.esm.common.error;

/**
 * The client tried to modify a resource based on an out-of-date copy (lost-update protection).
 *
 * <p>Clients send back the {@code version} they last read. We compare it explicitly, because
 * Hibernate's {@code @Version} check only covers the gap between our own read and write inside one
 * transaction, not the much longer gap while the user was looking at the screen.
 */
public class StaleVersionException extends DomainException {

    public StaleVersionException(String resourceType) {
        super(
                ErrorCode.CONCURRENT_MODIFICATION,
                "%s was modified by someone else. Reload it and try again.".formatted(resourceType));
    }

    public static void check(String resourceType, long expectedVersion, long actualVersion) {
        if (expectedVersion != actualVersion) {
            throw new StaleVersionException(resourceType);
        }
    }
}
