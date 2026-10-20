package io.floci.az.services.resourcegraph;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import io.floci.az.core.directory.Subscription;
import io.floci.az.core.directory.Tenant;
import io.floci.az.services.arm.ArmHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Azure Resource Graph: {@code POST /providers/Microsoft.ResourceGraph/resources}, the {@code Resources}
 * operation of the {@code 2024-04-01} spec. A {@link KqlQuery} runs over the {@code Resources} table (the
 * subscriptions' generic resource index) or the {@code ResourceContainers} table (the subscriptions and their
 * resource groups). Pages follow {@code options.$top}, {@code $skip} and {@code $skipToken}; results come as
 * an object array, or as columns and rows with {@code resultFormat: table}.
 */
@ApplicationScoped
public class ResourceGraphHandler implements AzureServiceHandler {

    static final String SERVICE_TYPE = "resourcegraph";

    private static final String RESOURCES_PATH = "providers/microsoft.resourcegraph/resources";
    private static final int DEFAULT_TOP = 100;
    private static final int MAX_TOP = 1000;

    private final EmulatorConfig config;
    private final ArmHandler arm;
    private final AzureDirectory directory;
    private final ResourceGroups groups;

    @Inject
    public ResourceGraphHandler(EmulatorConfig config, ArmHandler arm, AzureDirectory directory,
                                ResourceGroups groups) {
        this.config = config;
        this.arm = arm;
        this.directory = directory;
        this.groups = groups;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().resourceGraph().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder().provider("Microsoft.ResourceGraph").build();
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath().toLowerCase(Locale.ROOT);
        int query = path.indexOf('?');
        path = query < 0 ? path : path.substring(0, query);
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!path.equals(RESOURCES_PATH)) {
            return ArmErrors.notFound("Resource not found: " + req.resourcePath());
        }
        if (!"POST".equalsIgnoreCase(req.method())) {
            return Response.status(405).build();
        }
        Map<String, Object> body;
        try {
            body = ArmJson.parseBodyStrict(req);
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
        if (!(body.get("query") instanceof String text) || text.isBlank()) {
            return ArmErrors.error(400, "BadRequest", "The request body must contain a query.");
        }
        try {
            return run(KqlQuery.parse(text), body, req);
        } catch (KqlQuery.KqlException e) {
            return invalidQuery(e.getMessage());
        }
    }

    private Response run(KqlQuery query, Map<String, Object> body, AzureRequest req) {
        List<String> subscriptions = subscriptions(body, req);
        List<Map<String, Object>> rows = switch (query.table().toLowerCase(Locale.ROOT)) {
            case "resources" -> resourceRows(subscriptions);
            case "resourcecontainers" -> containerRows(subscriptions);
            default -> throw new KqlQuery.KqlException("Table '" + query.table()
                    + "' is not supported; use Resources or ResourceContainers");
        };
        List<Map<String, Object>> result = query.run(rows);

        Map<String, Object> options = ArmJson.cast(body.get("options"));
        int top = Math.max(1, Math.min(MAX_TOP, number(options.get("$top"), DEFAULT_TOP)));
        int skip = options.get("$skipToken") instanceof String token
                ? decodeSkipToken(token)
                : Math.max(0, number(options.get("$skip"), 0));
        int from = Math.min(skip, result.size());
        int to = Math.min(from + top, result.size());
        List<Map<String, Object>> page = result.subList(from, to);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalRecords", (long) result.size());
        response.put("count", (long) page.size());
        response.put("data", "table".equalsIgnoreCase(String.valueOf(options.get("resultFormat")))
                ? table(page)
                : page);
        response.put("facets", List.of());
        response.put("resultTruncated", "false");
        if (to < result.size()) {
            response.put("$skipToken", encodeSkipToken(to));
        }
        return Response.ok(response).type(MediaType.APPLICATION_JSON).build();
    }

    /** The subscriptions the body names, or else every subscription of the caller's tenant. */
    private List<String> subscriptions(Map<String, Object> body, AzureRequest req) {
        if (body.get("subscriptions") instanceof List<?> named && !named.isEmpty()) {
            return named.stream().map(String::valueOf).toList();
        }
        String tokenTenantId = req.scope() == null ? null : req.scope().tokenTenantId();
        String tenantId = directory.tenant(tokenTenantId).map(Tenant::id).orElse(directory.defaultTenantId());
        return directory.subscriptionsOfTenant(tenantId).stream().map(Subscription::id).toList();
    }

    private List<Map<String, Object>> resourceRows(List<String> subscriptions) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String sub : subscriptions) {
            String tenantId = directory.tenantOfSubscription(sub);
            for (Map<String, Object> resource : arm.subscriptionResources(sub)) {
                if (!(resource.get("id") instanceof String id) || !seen.add(id.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String resourceGroup = ArmResourceId.parse(id).map(ArmResourceId::resourceGroup).orElse(null);
                Map<String, Object> row = row(id, resource.get("name"), resource.get("type"), tenantId,
                        resource.get("location"), resourceGroup, sub, resource.get("tags"));
                row.put("kind", orEmpty(resource.get("kind")));
                row.put("managedBy", orEmpty(resource.get("managedBy")));
                for (String field : List.of("sku", "plan", "properties", "identity", "zones", "extendedLocation")) {
                    row.put(field, resource.get(field));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> containerRows(List<String> subscriptions) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String sub : subscriptions) {
            String tenantId = directory.tenantOfSubscription(sub);
            directory.subscription(sub).ifPresent(subscription -> {
                Map<String, Object> row = row("/subscriptions/" + subscription.id(), subscription.displayName(),
                        "microsoft.resources/subscriptions", tenantId, null, null, subscription.id(), null);
                row.put("properties", Map.of("state", subscription.state().wireName()));
                rows.add(row);
            });
            for (ResourceGroup group : groups.list(sub)) {
                Map<String, Object> row = row(group.id(), group.name(),
                        "microsoft.resources/subscriptions/resourcegroups", tenantId, group.location(), group.name(),
                        sub, group.tags());
                row.put("managedBy", orEmpty(group.managedBy()));
                row.put("properties", Map.of("provisioningState",
                        group.provisioningState() == null ? ResourceGroup.SUCCEEDED : group.provisioningState()));
                rows.add(row);
            }
        }
        return rows;
    }

    /** The columns every Resource Graph row carries; Resource Graph reports {@code type} in lowercase. */
    private static Map<String, Object> row(String id, Object name, Object type, String tenantId, Object location,
                                           String resourceGroup, String subscriptionId, Object tags) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("type", type == null ? "" : String.valueOf(type).toLowerCase(Locale.ROOT));
        row.put("tenantId", orEmpty(tenantId));
        row.put("kind", "");
        row.put("location", orEmpty(location));
        row.put("resourceGroup", orEmpty(resourceGroup));
        row.put("subscriptionId", subscriptionId);
        row.put("managedBy", "");
        row.put("tags", tags instanceof Map<?, ?> ? tags : Map.of());
        return row;
    }

    private static Object orEmpty(Object value) {
        return value == null ? "" : value;
    }

    /** Lays the page out as typed columns and value rows for {@code resultFormat: table}. */
    private static Map<String, Object> table(List<Map<String, Object>> page) {
        List<String> names = new ArrayList<>();
        page.forEach(row -> row.keySet().stream().filter(name -> !names.contains(name)).forEach(names::add));
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String name : names) {
            Object sample = page.stream().map(row -> row.get(name)).filter(value -> value != null).findFirst()
                    .orElse(null);
            columns.add(Map.of("name", name, "type", columnType(sample)));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : page) {
            List<Object> values = new ArrayList<>();
            names.forEach(name -> values.add(row.get(name)));
            rows.add(values);
        }
        return Map.of("columns", columns, "rows", rows);
    }

    private static String columnType(Object sample) {
        return switch (sample) {
            case Boolean ignored -> "boolean";
            case Integer ignored -> "integer";
            case Long ignored -> "integer";
            case Number ignored -> "real";
            case Map<?, ?> ignored -> "object";
            case List<?> ignored -> "object";
            case null, default -> "string";
        };
    }

    private static int number(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                // A non-numeric option falls back to the default, as an absent one does.
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** An opaque token for the next page: the offset it starts at. */
    private static String encodeSkipToken(int offset) {
        return Base64.getEncoder().encodeToString(("{\"skip\":" + offset + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeSkipToken(String token) {
        try {
            String json = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
            return Integer.parseInt(json.replaceAll("[^0-9]", ""));
        } catch (IllegalArgumentException e) {
            throw new KqlQuery.KqlException("The $skipToken is not one this service issued");
        }
    }

    /** Answers 400 {@code BadRequest} with the {@code InvalidQuery} and {@code ParserFailure} details. */
    private static Response invalidQuery(String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "BadRequest");
        error.put("message", "Please provide below info when asking for support: query is invalid.");
        error.put("details", List.of(
                Map.of("code", "InvalidQuery", "message", "Query is invalid. Please refer to the documentation for "
                        + "the Azure Resource Graph service and fix the error before retrying."),
                Map.of("code", "ParserFailure", "message", detail)));
        return Response.status(400).entity(Map.of("error", error)).type(MediaType.APPLICATION_JSON).build();
    }
}
