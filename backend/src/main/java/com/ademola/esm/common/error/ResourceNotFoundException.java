package com.ademola.esm.common.error;

/**
 * Thrown when a resource does not exist <em>or the caller is not allowed to know it exists</em>.
 * Returning 404 rather than 403 for other users' tickets prevents ID enumeration.
 */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resourceType, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "%s %s was not found".formatted(resourceType, id));
    }
}
