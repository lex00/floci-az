package io.floci.az.services.rbac;

import io.floci.az.core.arm.ArmResourceId;

import java.util.Locale;
import java.util.Optional;

/**
 * A {@code Microsoft.Authorization} role-based access control path at any scope: role definitions, role
 * assignments, or the caller's permissions. The scope is everything before the last {@code providers}
 * section, so an assignment on a storage account and one on a subscription parse alike.
 *
 * @param kind  what the path addresses
 * @param scope the scope as an ARM id, or {@code /} at the tenant root
 * @param name  the definition or assignment name, or null for a collection
 */
public record RbacPath(Kind kind, String scope, String name) {

    public enum Kind { ROLE_DEFINITION, ROLE_ASSIGNMENT, PERMISSIONS }

    private static final String NAMESPACE = "Microsoft.Authorization";

    /** The parsed path when it addresses a role definition, role assignment or permissions; empty otherwise. */
    public static Optional<RbacPath> parse(String path) {
        Optional<ArmResourceId> parsed = ArmResourceId.parse(path);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        ArmResourceId id = parsed.get();
        if (!NAMESPACE.equalsIgnoreCase(id.providerNamespace()) || id.types().size() != 1) {
            return Optional.empty();
        }
        Kind kind = switch (id.types().getFirst().toLowerCase(Locale.ROOT)) {
            case "roledefinitions" -> Kind.ROLE_DEFINITION;
            case "roleassignments" -> Kind.ROLE_ASSIGNMENT;
            case "permissions" -> Kind.PERMISSIONS;
            default -> null;
        };
        if (kind == null || kind == Kind.PERMISSIONS && !id.isCollection()) {
            return Optional.empty();
        }
        String scope = id.scope() == null ? "/" : id.scope().id();
        return Optional.of(new RbacPath(kind, scope, id.isCollection() ? null : id.names().getFirst()));
    }

    public static boolean matches(String path) {
        return parse(path).isPresent();
    }

    /** Whether {@code ancestor} is {@code scope} itself or a scope above it; {@code /} is above every scope. */
    public static boolean covers(String ancestor, String scope) {
        if (ancestor.equals("/")) {
            return true;
        }
        String a = ancestor.toLowerCase(Locale.ROOT);
        String s = scope.toLowerCase(Locale.ROOT);
        return s.equals(a) || s.startsWith(a + "/");
    }

    /** The {@code /subscriptions/{sub}} part of {@code scope}, or the empty string when it names none. */
    public static String subscriptionPrefix(String scope) {
        return ArmResourceId.parse(scope)
                .map(ArmResourceId::subscriptionId)
                .map(sub -> "/subscriptions/" + sub)
                .orElse("");
    }
}
