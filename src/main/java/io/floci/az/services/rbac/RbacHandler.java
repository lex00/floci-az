package io.floci.az.services.rbac;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.CallerPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Azure role-based access control under {@code Microsoft.Authorization} at any scope. Role definitions are the
 * built-ins in {@link BuiltInRoles} plus custom roles, role assignments keep their ABAC {@code condition}, and
 * {@code permissions} reports what the caller holds. Shapes follow the authorization spec {@code 2022-04-01};
 * any {@code api-version} is accepted.
 *
 * <p>The router sends these paths here ahead of the provider routes, since an assignment on a resource sits
 * under that resource's own provider path.
 */
@ApplicationScoped
public class RbacHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(RbacHandler.class);

    static final String SERVICE_TYPE = "rbac";
    private static final Pattern FILTER_EQUALS = Pattern.compile("(\\w+)\\s+eq\\s+'([^']*)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIGNED_TO = Pattern.compile("assignedTo\\('([^']*)'\\)", Pattern.CASE_INSENSITIVE);

    private final EmulatorConfig config;
    private final RbacStore store;

    @Inject
    public RbacHandler(EmulatorConfig config, RbacStore store) {
        this.config = config;
        this.store = store;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().rbac().enabled();
    }

    /**
     * No provider route: {@code Microsoft.Authorization} is Azure Policy's route, and a router stage of its own
     * sends RBAC paths here.
     */
    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder().build();
    }

    @Override
    public Response handle(AzureRequest req) {
        Optional<RbacPath> parsed = RbacPath.parse(req.resourcePath());
        if (parsed.isEmpty()) {
            return ArmErrors.notFound("Resource not found: " + req.resourcePath());
        }
        RbacPath path = parsed.get();
        String method = req.method().toUpperCase(Locale.ROOT);
        String filter = req.queryParams() == null ? null : req.queryParams().get("$filter");
        try {
            return switch (path.kind()) {
                case ROLE_DEFINITION -> path.name() == null
                        ? onlyGet(method, () -> listDefinitions(path.scope(), filter))
                        : definition(req, path, method);
                case ROLE_ASSIGNMENT -> path.name() == null
                        ? onlyGet(method, () -> listAssignments(path.scope(), filter))
                        : assignment(req, path, method);
                case PERMISSIONS -> onlyGet(method, () -> permissions(req, path.scope()));
            };
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
    }

    private interface Answer {
        Response get();
    }

    private static Response onlyGet(String method, Answer answer) {
        return "GET".equals(method) ? answer.get() : Response.status(405).build();
    }

    // ── Role definitions ────────────────────────────────────────────────────────

    private Response definition(AzureRequest req, RbacPath path, String method) {
        return switch (method) {
            case "GET" -> store.roleDefinition(path.name(), path.scope())
                    .map(body -> Response.ok(body).build())
                    .orElseGet(() -> roleDefinitionNotFound(path.name()));
            case "PUT" -> putDefinition(req, path);
            case "DELETE" -> deleteDefinition(path);
            default -> Response.status(405).build();
        };
    }

    private synchronized Response putDefinition(AzureRequest req, RbacPath path) {
        if (!isGuid(path.name())) {
            return ArmErrors.error(400, "InvalidRoleDefinitionId",
                    "The role definition ID '" + path.name() + "' is not valid. The role definition ID must be a GUID.");
        }
        if (BuiltInRoles.byId(path.name()).isPresent()) {
            return builtInReadOnly(path.name());
        }
        Map<String, Object> requested = RbacStore.properties(ArmJson.parseBodyStrict(req));
        if (!(requested.get("roleName") instanceof String roleName) || roleName.isBlank()) {
            return ArmErrors.error(400, "InvalidRequestContent", "The role definition must have a roleName.");
        }
        boolean nameTaken = BuiltInRoles.byName(roleName).isPresent() || store.customRoles().stream()
                .anyMatch(other -> !path.name().equalsIgnoreCase(String.valueOf(other.get("name")))
                        && roleName.equalsIgnoreCase(String.valueOf(RbacStore.properties(other).get("roleName"))));
        if (nameTaken) {
            return ArmErrors.error(409, "RoleDefinitionWithSameNameExists",
                    "A custom role with the same name already exists in this directory. Use a different name.");
        }
        Optional<Map<String, Object>> existing = store.customRole(path.name());
        String now = Instant.now().toString();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roleName", roleName);
        properties.put("type", "CustomRole");
        properties.put("description", requested.getOrDefault("description", ""));
        properties.put("assignableScopes", requested.get("assignableScopes") instanceof List<?> scopes
                ? scopes
                : List.of(path.scope()));
        properties.put("permissions", requested.get("permissions") instanceof List<?> permissions
                ? permissions
                : List.of());
        properties.put("createdOn", existing.map(body -> RbacStore.properties(body).get("createdOn")).orElse(now));
        properties.put("updatedOn", now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", (path.scope().equals("/") ? "" : path.scope()) + "/providers/" + RbacStore.DEFINITION_TYPE
                + "/" + path.name());
        body.put("name", path.name());
        body.put("type", RbacStore.DEFINITION_TYPE);
        body.put("properties", properties);
        store.putCustomRole(path.name(), body);
        LOG.infov("RBAC: stored custom role {0} ({1})", roleName, path.name());
        return json(existing.isPresent() ? 200 : 201, body);
    }

    private synchronized Response deleteDefinition(RbacPath path) {
        if (BuiltInRoles.byId(path.name()).isPresent()) {
            return builtInReadOnly(path.name());
        }
        boolean assigned = store.assignments().stream().anyMatch(assignment -> path.name().equalsIgnoreCase(
                lastSegment(String.valueOf(RbacStore.properties(assignment).get("roleDefinitionId")))));
        if (assigned) {
            return ArmErrors.error(409, "RoleDefinitionHasAssignments",
                    "There are existing role assignments referencing role (code: RoleDefinitionHasAssignments).");
        }
        return store.removeCustomRole(path.name())
                .map(body -> Response.ok(body).build())
                .orElseGet(() -> Response.noContent().build());
    }

    /** Built-in and custom roles assignable at {@code scope}, narrowed by {@code roleName eq} or {@code type eq}. */
    private Response listDefinitions(String scope, String filter) {
        Map<String, String> equals = filterEquals(filter);
        List<Map<String, Object>> value = new ArrayList<>();
        BuiltInRoles.ALL.forEach(role -> value.add(RbacStore.builtInBody(role, scope)));
        for (Map<String, Object> custom : store.customRoles()) {
            Object scopes = RbacStore.properties(custom).get("assignableScopes");
            if (scopes instanceof List<?> list && list.stream().anyMatch(s -> RbacPath.covers(String.valueOf(s), scope))) {
                value.add(custom);
            }
        }
        List<Map<String, Object>> matching = value.stream().filter(body -> {
            Map<String, Object> properties = RbacStore.properties(body);
            return matchesIgnoreCase(equals.get("rolename"), properties.get("roleName"))
                    && matchesIgnoreCase(equals.get("type"), properties.get("type"));
        }).toList();
        return Response.ok(Map.of("value", matching)).build();
    }

    // ── Role assignments ────────────────────────────────────────────────────────

    private Response assignment(AzureRequest req, RbacPath path, String method) {
        return switch (method) {
            case "GET" -> store.assignment(path.name())
                    .filter(body -> sameScope(body, path.scope()))
                    .map(body -> Response.ok(body).build())
                    .orElseGet(() -> roleAssignmentNotFound());
            case "PUT" -> putAssignment(req, path);
            case "DELETE" -> deleteAssignment(path);
            default -> Response.status(405).build();
        };
    }

    private synchronized Response putAssignment(AzureRequest req, RbacPath path) {
        if (!isGuid(path.name())) {
            return ArmErrors.error(400, "InvalidRoleAssignmentId", "The role assignment ID '" + path.name()
                    + "' is not valid. The role assignment ID must be a GUID.");
        }
        Map<String, Object> requested = RbacStore.properties(ArmJson.parseBodyStrict(req));
        String roleDefinitionId = stringOrNull(requested.get("roleDefinitionId"));
        String principalId = stringOrNull(requested.get("principalId"));
        if (roleDefinitionId == null || principalId == null) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The role assignment must have a roleDefinitionId and a principalId.");
        }
        if (store.roleDefinition(roleDefinitionId, path.scope()).isEmpty()) {
            return ArmErrors.error(400, "RoleDefinitionDoesNotExist", "The specified role definition with ID '"
                    + lastSegment(roleDefinitionId) + "' does not exist.");
        }
        Optional<Map<String, Object>> existing = store.assignment(path.name());
        if (existing.isPresent()) {
            Map<String, Object> stored = RbacStore.properties(existing.get());
            boolean same = sameScope(existing.get(), path.scope())
                    && principalId.equalsIgnoreCase(String.valueOf(stored.get("principalId")))
                    && lastSegment(roleDefinitionId).equalsIgnoreCase(
                            lastSegment(String.valueOf(stored.get("roleDefinitionId"))));
            if (!same) {
                return ArmErrors.error(400, "RoleAssignmentUpdateNotPermitted",
                        "Tenant ID, application ID, principal ID, and scope are not allowed to be updated.");
            }
        } else {
            boolean duplicate = store.assignments().stream().map(RbacStore::properties).anyMatch(other ->
                    principalId.equalsIgnoreCase(String.valueOf(other.get("principalId")))
                            && path.scope().equalsIgnoreCase(String.valueOf(other.get("scope")))
                            && lastSegment(roleDefinitionId).equalsIgnoreCase(
                                    lastSegment(String.valueOf(other.get("roleDefinitionId")))));
            if (duplicate) {
                return ArmErrors.error(409, "RoleAssignmentExists", "The role assignment already exists.");
            }
        }
        String condition = stringOrNull(requested.get("condition"));
        String caller = CallerPrincipal.objectId(req).orElse(null);
        String now = Instant.now().toString();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roleDefinitionId", roleDefinitionId);
        properties.put("principalId", principalId);
        properties.put("principalType", Objects.requireNonNullElse(stringOrNull(requested.get("principalType")), "User"));
        properties.put("scope", path.scope());
        properties.put("condition", condition);
        properties.put("conditionVersion", condition == null
                ? null
                : Objects.requireNonNullElse(stringOrNull(requested.get("conditionVersion")), "2.0"));
        properties.put("description", requested.get("description"));
        properties.put("delegatedManagedIdentityResourceId", requested.get("delegatedManagedIdentityResourceId"));
        properties.put("createdOn", existing.map(body -> RbacStore.properties(body).get("createdOn")).orElse(now));
        properties.put("updatedOn", now);
        properties.put("createdBy", existing.map(body -> RbacStore.properties(body).get("createdBy")).orElse(caller));
        properties.put("updatedBy", caller);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", (path.scope().equals("/") ? "" : path.scope()) + "/providers/" + RbacStore.ASSIGNMENT_TYPE
                + "/" + path.name());
        body.put("name", path.name());
        body.put("type", RbacStore.ASSIGNMENT_TYPE);
        body.put("properties", properties);
        store.putAssignment(path.name(), body);
        LOG.infov("RBAC: assigned {0} to {1} at {2}", lastSegment(roleDefinitionId), principalId, path.scope());
        return json(existing.isPresent() ? 200 : 201, body);
    }

    private synchronized Response deleteAssignment(RbacPath path) {
        Optional<Map<String, Object>> existing = store.assignment(path.name())
                .filter(body -> sameScope(body, path.scope()));
        if (existing.isEmpty()) {
            return Response.noContent().build();
        }
        store.removeAssignment(path.name());
        return Response.ok(existing.get()).build();
    }

    /**
     * Assignments at, above and below {@code scope}. {@code atScope()} keeps those at or above it;
     * {@code principalId eq '...'} and {@code assignedTo('...')} keep one principal's.
     */
    private Response listAssignments(String scope, String filter) {
        boolean atScope = filter != null && filter.toLowerCase(Locale.ROOT).contains("atscope()");
        String principal = filterEquals(filter).get("principalid");
        if (filter != null) {
            Matcher assignedTo = ASSIGNED_TO.matcher(filter);
            if (assignedTo.find()) {
                principal = assignedTo.group(1);
            }
        }
        String wanted = principal;
        List<Map<String, Object>> value = store.assignments().stream().filter(body -> {
            Map<String, Object> properties = RbacStore.properties(body);
            String assigned = String.valueOf(properties.get("scope"));
            boolean inScope = RbacPath.covers(assigned, scope) || !atScope && RbacPath.covers(scope, assigned);
            return inScope && (wanted == null || wanted.equalsIgnoreCase(String.valueOf(properties.get("principalId"))));
        }).toList();
        return Response.ok(Map.of("value", value)).build();
    }

    // ── Permissions ─────────────────────────────────────────────────────────────

    /** What the caller's role assignments allow at {@code scope}; a caller without a token holds nothing. */
    private Response permissions(AzureRequest req, String scope) {
        List<Map<String, Object>> value = CallerPrincipal.objectId(req)
                .map(principal -> store.permissions(principal, scope))
                .orElse(List.of());
        return Response.ok(Map.of("value", value)).build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static boolean sameScope(Map<String, Object> body, String scope) {
        return scope.equalsIgnoreCase(String.valueOf(RbacStore.properties(body).get("scope")));
    }

    private static Map<String, String> filterEquals(String filter) {
        Map<String, String> equals = new LinkedHashMap<>();
        if (filter != null) {
            Matcher matcher = FILTER_EQUALS.matcher(filter);
            while (matcher.find()) {
                equals.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
            }
        }
        return equals;
    }

    private static boolean matchesIgnoreCase(String wanted, Object actual) {
        return wanted == null || wanted.equalsIgnoreCase(String.valueOf(actual));
    }

    private static boolean isGuid(String value) {
        try {
            UUID.fromString(value);
            return value.length() == 36;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String lastSegment(String id) {
        return id.substring(id.lastIndexOf('/') + 1);
    }

    private static String stringOrNull(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Response json(int status, Map<String, Object> body) {
        return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    private static Response roleDefinitionNotFound(String name) {
        return ArmErrors.error(404, "RoleDefinitionDoesNotExist",
                "The specified role definition with ID '" + name + "' does not exist.");
    }

    private static Response roleAssignmentNotFound() {
        return ArmErrors.error(404, "RoleAssignmentNotFound", "The role assignment does not exist.");
    }

    /** Built-in roles cannot change; floci-az answers as Azure does to a caller without write access to them. */
    private static Response builtInReadOnly(String name) {
        return ArmErrors.error(403, "AuthorizationFailed",
                "The built-in role definition '" + name + "' cannot be modified or deleted.");
    }
}
