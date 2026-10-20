package io.floci.az.core.arm;

import io.floci.az.core.cloud.ScopeContext;

import java.util.Map;

/**
 * One ARM request as the {@link ArmRequestGate} sees it, built once on entry and handed to every rule.
 *
 * @param scope              the request's resolved scope; null only for a request built outside the routing filter
 * @param id                 the parsed path, or null when the path does not start at an ARM root
 * @param type               the matched resource type, such as {@code Microsoft.Storage/storageAccounts}; null
 *                           until resource types are registered
 * @param method             the HTTP method, uppercase
 * @param normalizedLocation the request body's location in canonical form; null until the location rule reads it
 * @param callerObjectId     the {@code oid} claim of the request's bearer token, or null when it carries none
 * @param body               the JSON body of a {@code PUT} or {@code PATCH}, empty when it is not an object; null
 *                           for other methods
 */
public record ArmCall(ScopeContext scope, ArmResourceId id, String type, String method, String normalizedLocation,
                      String callerObjectId, Map<String, Object> body) {

    /** A call whose caller and body are not known. */
    public ArmCall(ScopeContext scope, ArmResourceId id, String type, String method, String normalizedLocation) {
        this(scope, id, type, method, normalizedLocation, null, null);
    }
}
