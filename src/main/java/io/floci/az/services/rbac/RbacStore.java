package io.floci.az.services.rbac;

import io.floci.az.core.Resettable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom role definitions and role assignments, held as their wire bodies and keyed by their GUID names, which
 * Azure keeps unique across the tenant. Built-in roles come from {@link BuiltInRoles}.
 */
@ApplicationScoped
public class RbacStore implements Resettable {

    static final String DEFINITION_TYPE = "Microsoft.Authorization/roleDefinitions";
    static final String ASSIGNMENT_TYPE = "Microsoft.Authorization/roleAssignments";

    private final Map<String, Map<String, Object>> customRoles = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> assignments = new ConcurrentHashMap<>();

    Optional<Map<String, Object>> customRole(String name) {
        return Optional.ofNullable(customRoles.get(key(name)));
    }

    List<Map<String, Object>> customRoles() {
        return List.copyOf(customRoles.values());
    }

    void putCustomRole(String name, Map<String, Object> body) {
        customRoles.put(key(name), body);
    }

    Optional<Map<String, Object>> removeCustomRole(String name) {
        return Optional.ofNullable(customRoles.remove(key(name)));
    }

    Optional<Map<String, Object>> assignment(String name) {
        return Optional.ofNullable(assignments.get(key(name)));
    }

    List<Map<String, Object>> assignments() {
        return List.copyOf(assignments.values());
    }

    void putAssignment(String name, Map<String, Object> body) {
        assignments.put(key(name), body);
    }

    Optional<Map<String, Object>> removeAssignment(String name) {
        return Optional.ofNullable(assignments.remove(key(name)));
    }

    /**
     * The role definition {@code roleDefinitionId} names, by its last segment: a built-in's body as read at
     * {@code scope}, or a custom role's stored body.
     */
    Optional<Map<String, Object>> roleDefinition(String roleDefinitionId, String scope) {
        String name = roleDefinitionId.substring(roleDefinitionId.lastIndexOf('/') + 1);
        return BuiltInRoles.byId(name).map(role -> builtInBody(role, scope)).or(() -> customRole(name));
    }

    /**
     * The permissions {@code principalId} holds at {@code scope}: one entry per role assignment at that scope or
     * above it, each the assigned role's permissions with the assignment's condition.
     */
    public List<Map<String, Object>> permissions(String principalId, String scope) {
        List<Map<String, Object>> permissions = new ArrayList<>();
        for (Map<String, Object> assignment : assignments.values()) {
            Map<String, Object> properties = properties(assignment);
            if (!principalId.equalsIgnoreCase(String.valueOf(properties.get("principalId")))
                    || !RbacPath.covers(String.valueOf(properties.get("scope")), scope)) {
                continue;
            }
            roleDefinition(String.valueOf(properties.get("roleDefinitionId")), scope).ifPresent(role -> {
                for (Object raw : (List<?>) properties(role).get("permissions")) {
                    Map<String, Object> permission = new LinkedHashMap<>();
                    ((Map<?, ?>) raw).forEach((k, v) -> permission.put(String.valueOf(k), v));
                    if (properties.get("condition") != null) {
                        permission.put("condition", properties.get("condition"));
                        permission.put("conditionVersion", properties.get("conditionVersion"));
                    }
                    permissions.add(permission);
                }
            });
        }
        return permissions;
    }

    /** A built-in role's body; Azure reports built-ins under the subscription of the scope they are read at. */
    static Map<String, Object> builtInBody(BuiltInRoles.Role role, String scope) {
        Map<String, Object> permission = new LinkedHashMap<>();
        permission.put("actions", role.permission().actions());
        permission.put("notActions", role.permission().notActions());
        permission.put("dataActions", role.permission().dataActions());
        permission.put("notDataActions", role.permission().notDataActions());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roleName", role.roleName());
        properties.put("type", "BuiltInRole");
        properties.put("description", role.description());
        properties.put("assignableScopes", List.of("/"));
        properties.put("permissions", List.of(permission));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", RbacPath.subscriptionPrefix(scope) + "/providers/" + DEFINITION_TYPE + "/" + role.id());
        body.put("name", role.id());
        body.put("type", DEFINITION_TYPE);
        body.put("properties", properties);
        return body;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> properties(Map<String, Object> body) {
        return body.get("properties") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    @Override
    public void clear() {
        customRoles.clear();
        assignments.clear();
    }
}
