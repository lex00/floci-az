package io.floci.az.services.deployments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import io.floci.az.services.arm.ArmHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Template deployments, {@code Microsoft.Resources/deployments}, at resource-group and subscription scope:
 * create (which deploys the template), get, check existence, list, delete, the {@code operations} of a
 * deployment, and the {@code validate} and {@code exportTemplate} actions. Shapes follow the resources spec
 * {@code 2024-03-01}; any {@code api-version} is accepted.
 *
 * <p>A deployment runs to completion inside its {@code PUT}, which answers {@code 201} (or {@code 200} for a
 * name already used) with the final {@code provisioningState}, {@code Succeeded} or {@code Failed}. Each
 * template resource is provisioned by an internal {@code PUT} through the router, so it reaches the same handler,
 * RBAC and policy checks a direct request would. A template that fails validation answers {@code 400}.
 */
@ApplicationScoped
public class DeploymentsHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(DeploymentsHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SERVICE_TYPE = "deployments";
    private static final String TYPE = "Microsoft.Resources/deployments";
    private static final String DEPLOYMENT_FAILED = "At least one resource deployment operation failed. Please list "
            + "deployment operations for details. Please see https://aka.ms/arm-deployment-operations for usage "
            + "details.";

    private record Deployment(Map<String, Object> body, Map<String, Object> template,
                              List<Map<String, Object>> operations) {
    }

    private final Map<String, Deployment> deployments = new ConcurrentHashMap<>();
    private final EmulatorConfig config;
    private final Instance<ArmRouter> router;
    private final Instance<ArmHandler> arm;
    private final AzureDirectory directory;
    private final ResourceGroups groups;

    @Inject
    public DeploymentsHandler(EmulatorConfig config, Instance<ArmRouter> router, Instance<ArmHandler> arm,
                              AzureDirectory directory, ResourceGroups groups) {
        this.config = config;
        this.router = router;
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
        return config.services().deployments().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder().provider("Microsoft.Resources").build();
    }

    @Override
    public Response handle(AzureRequest req) {
        Optional<ArmResourceId> parsed = ArmResourceId.parse(req.resourcePath());
        if (parsed.isEmpty() || parsed.get().subscriptionId() == null || parsed.get().types().isEmpty()
                || !parsed.get().types().getFirst().equalsIgnoreCase("deployments")) {
            return ArmErrors.notFound("Resource not found: " + req.resourcePath());
        }
        ArmResourceId id = parsed.get();
        String method = req.method().toUpperCase(Locale.ROOT);
        List<String> types = id.types();
        List<String> names = id.names();
        try {
            if (types.size() == 1 && names.isEmpty()) {
                return "GET".equals(method) ? list(id) : Response.status(405).build();
            }
            String key = deploymentId(id).toLowerCase(Locale.ROOT);
            if (types.size() == 1) {
                return switch (method) {
                    case "PUT" -> put(req, id);
                    case "GET" -> Optional.ofNullable(deployments.get(key))
                            .map(d -> Response.ok(d.body()).build())
                            .orElseGet(() -> notFound(id));
                    case "HEAD" -> deployments.containsKey(key)
                            ? Response.noContent().build()
                            : Response.status(404).build();
                    case "DELETE" -> {
                        // The record goes at once, so there is nothing left to poll; the resources stay.
                        deployments.remove(key);
                        yield Response.noContent().build();
                    }
                    default -> Response.status(405).build();
                };
            }
            String action = types.get(1).toLowerCase(Locale.ROOT);
            return switch (action) {
                case "operations" -> operations(key, id, names.size() > 1 ? names.get(1) : null);
                case "validate" -> "POST".equals(method) ? validate(req, id) : Response.status(405).build();
                case "exporttemplate" -> "POST".equals(method)
                        ? Optional.ofNullable(deployments.get(key))
                                .map(d -> Response.ok(Map.of("template", d.template())).build())
                                .orElseGet(() -> notFound(id))
                        : Response.status(405).build();
                default -> ArmErrors.notFound("Resource not found: " + req.resourcePath());
            };
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
    }

    // ── Create ──────────────────────────────────────────────────────────────────

    private Response put(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Optional<Response> invalid = checkRequest(body, id);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        Map<String, Object> properties = ArmJson.cast(body.get("properties"));
        Map<String, Object> template = ArmJson.cast(properties.get("template"));
        boolean complete = "Complete".equalsIgnoreCase(String.valueOf(properties.get("mode")));
        TemplateEngine engine = new TemplateEngine(scope(id), armFor(req, id));
        Map<String, Object> parameters;
        try {
            parameters = engine.bindParameters(template, ArmJson.cast(properties.get("parameters")));
            engine.validate(template, parameters);
        } catch (TemplateException e) {
            return ArmErrors.error(400, e.code(), e.getMessage());
        }
        Instant started = Instant.now();
        TemplateEngine.Outcome outcome = engine.deploy(template, parameters, complete);
        String deploymentId = deploymentId(id);
        boolean existed = deployments.containsKey(deploymentId.toLowerCase(Locale.ROOT));
        Map<String, Object> result = deploymentBody(id, body, template, outcome, started, complete);
        List<Map<String, Object>> operations = operationBodies(deploymentId, outcome, started);
        deployments.put(deploymentId.toLowerCase(Locale.ROOT), new Deployment(result, template, operations));
        LOG.infov("ARM: deployment {0} {1} with {2} operation(s)", deploymentId,
                ArmJson.cast(result.get("properties")).get("provisioningState"), operations.size());
        return Response.status(existed ? 200 : 201).entity(result).type(MediaType.APPLICATION_JSON).build();
    }

    /** The request-level errors Azure answers before a deployment starts. */
    private Optional<Response> checkRequest(Map<String, Object> body, ArmResourceId id) {
        Map<String, Object> properties = ArmJson.cast(body.get("properties"));
        if (properties.get("templateLink") != null || properties.get("parametersLink") != null) {
            return Optional.of(ArmErrors.error(400, "InvalidTemplate",
                    "floci-az does not fetch templateLink or parametersLink; send the template inline."));
        }
        if (!(properties.get("template") instanceof Map<?, ?>)) {
            return Optional.of(ArmErrors.error(400, "InvalidRequestContent",
                    "The deployment must have a 'template' in its properties."));
        }
        Object mode = properties.getOrDefault("mode", "Incremental");
        String modeName = String.valueOf(mode);
        if (!"Incremental".equalsIgnoreCase(modeName) && !"Complete".equalsIgnoreCase(modeName)) {
            return Optional.of(ArmErrors.error(400, "InvalidRequestContent",
                    "The deployment mode '" + mode + "' is not valid; use 'Incremental' or 'Complete'."));
        }
        if (id.resourceGroup() == null && !(body.get("location") instanceof String)) {
            return Optional.of(ArmErrors.error(400, "LocationRequired",
                    "The location property is required for this definition."));
        }
        if (id.resourceGroup() != null && groups.get(id.subscriptionId(), id.resourceGroup()).isEmpty()) {
            return Optional.of(ArmErrors.resourceGroupNotFound(id.resourceGroup()));
        }
        return Optional.empty();
    }

    private Response validate(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Optional<Response> invalid = checkRequest(body, id);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        Map<String, Object> properties = ArmJson.cast(body.get("properties"));
        Map<String, Object> template = ArmJson.cast(properties.get("template"));
        TemplateEngine engine = new TemplateEngine(scope(id), armFor(req, id));
        try {
            Map<String, Object> parameters = engine.bindParameters(template,
                    ArmJson.cast(properties.get("parameters")));
            List<String> validated = engine.validate(template, parameters);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("templateHash", templateHash(template));
            result.put("parameters", typedParameters(template, parameters));
            result.put("mode", properties.getOrDefault("mode", "Incremental"));
            result.put("provisioningState", "Succeeded");
            result.put("validatedResources", validated.stream().map(resourceId -> Map.of("id", resourceId)).toList());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", deploymentId(id));
            response.put("name", id.names().getFirst());
            response.put("type", TYPE);
            response.put("properties", result);
            return Response.ok(response).type(MediaType.APPLICATION_JSON).build();
        } catch (TemplateException e) {
            return ArmErrors.error(400, e.code(), e.getMessage());
        }
    }

    // ── Reads ───────────────────────────────────────────────────────────────────

    private Response list(ArmResourceId collection) {
        String prefix = (collection.parent() == null ? "" : collection.parent().id()).toLowerCase(Locale.ROOT)
                + "/providers/microsoft.resources/deployments/";
        List<Map<String, Object>> value = deployments.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(entry -> entry.getValue().body())
                .toList();
        return Response.ok(Map.of("value", value)).build();
    }

    private Response operations(String key, ArmResourceId id, String operationId) {
        Deployment deployment = deployments.get(key);
        if (deployment == null) {
            return notFound(id);
        }
        if (operationId == null) {
            return Response.ok(Map.of("value", deployment.operations())).build();
        }
        return deployment.operations().stream()
                .filter(op -> operationId.equalsIgnoreCase(String.valueOf(op.get("operationId"))))
                .findFirst()
                .map(op -> Response.ok(op).build())
                .orElseGet(() -> ArmErrors.notFound("The deployment operation '" + operationId + "' is not found."));
    }

    // ── Bodies ──────────────────────────────────────────────────────────────────

    private Map<String, Object> deploymentBody(ArmResourceId id, Map<String, Object> request,
                                               Map<String, Object> template, TemplateEngine.Outcome outcome,
                                               Instant started, boolean complete) {
        boolean failed = outcome.error() != null || outcome.failed() != null;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("templateHash", templateHash(template));
        properties.put("parameters", typedParameters(template, outcome.parameters()));
        properties.put("mode", complete ? "Complete" : "Incremental");
        properties.put("provisioningState", failed ? "Failed" : "Succeeded");
        properties.put("timestamp", Instant.now().toString());
        properties.put("duration", Duration.between(started, Instant.now()).toString());
        properties.put("correlationId", UUID.randomUUID().toString());
        properties.put("providers", List.of());
        properties.put("dependencies", List.of());
        properties.put("outputs", outcome.outputs());
        properties.put("outputResources", outcome.outputResources().stream().map(r -> Map.of("id", r)).toList());
        if (failed) {
            properties.put("error", error(outcome));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", deploymentId(id));
        body.put("name", id.names().getFirst());
        body.put("type", TYPE);
        if (request.get("location") instanceof String location) {
            body.put("location", location);
        }
        if (request.get("tags") instanceof Map<?, ?> tags) {
            body.put("tags", tags);
        }
        body.put("properties", properties);
        return body;
    }

    /** The template's own error, or {@code DeploymentFailed} carrying the failed resource's error. */
    private static Map<String, Object> error(TemplateEngine.Outcome outcome) {
        if (outcome.error() != null) {
            return Map.of("code", outcome.error().code(), "message", outcome.error().getMessage());
        }
        Object raw = outcome.failed().result().body();
        Map<String, Object> inner = ArmJson.cast(ArmJson.cast(raw).get("error"));
        String code = String.valueOf(inner.getOrDefault("code", "ResourceDeploymentFailure"));
        String message = String.valueOf(inner.getOrDefault("message", "The resource operation completed with "
                + "terminal provisioning state 'Failed'."));
        return Map.of("code", "DeploymentFailed", "message", DEPLOYMENT_FAILED,
                "details", List.of(Map.of("code", code, "message", message)));
    }

    private static List<Map<String, Object>> operationBodies(String deploymentId, TemplateEngine.Outcome outcome,
                                                             Instant started) {
        List<Map<String, Object>> operations = new ArrayList<>();
        for (TemplateEngine.Operation operation : outcome.operations()) {
            String operationId = UUID.nameUUIDFromBytes((deploymentId + operation.resourceId() + started)
                    .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "").toUpperCase(Locale.ROOT)
                    .substring(0, 16);
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("provisioningOperation", operation.provisioningOperation());
            properties.put("provisioningState", operation.result().succeeded() ? "Succeeded" : "Failed");
            properties.put("timestamp", Instant.now().toString());
            properties.put("duration", Duration.between(started, Instant.now()).toString());
            properties.put("trackingId", UUID.randomUUID().toString());
            properties.put("statusCode", statusName(operation.result().status()));
            if (!operation.result().succeeded()) {
                properties.put("statusMessage", operation.result().body());
            }
            properties.put("targetResource", Map.of("id", operation.resourceId(),
                    "resourceType", operation.resourceType(), "resourceName", operation.resourceName()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", deploymentId + "/operations/" + operationId);
            body.put("operationId", operationId);
            body.put("properties", properties);
            operations.add(body);
        }
        return operations;
    }

    private static String statusName(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "NoContent";
            case 400 -> "BadRequest";
            case 403 -> "Forbidden";
            case 404 -> "NotFound";
            case 409 -> "Conflict";
            default -> String.valueOf(status);
        };
    }

    /** The bound parameters as the deployment reports them, each with its declared type. */
    private static Map<String, Object> typedParameters(Map<String, Object> template, Map<String, Object> bound) {
        Map<String, Object> declared = ArmJson.cast(template.get("parameters"));
        Map<String, Object> typed = new LinkedHashMap<>();
        bound.forEach((name, value) -> {
            String type = String.valueOf(ArmJson.cast(declared.get(name)).getOrDefault("type", "String"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", type);
            if (!type.toLowerCase(Locale.ROOT).startsWith("secure")) {
                entry.put("value", value);
            }
            typed.put(name, entry);
        });
        return typed;
    }

    private static String templateHash(Map<String, Object> template) {
        try {
            return Long.toUnsignedString(Integer.toUnsignedLong(MAPPER.writeValueAsString(template).hashCode()));
        } catch (JsonProcessingException e) {
            return "0";
        }
    }

    // ── Scope and the internal ARM bridge ───────────────────────────────────────

    private static String deploymentId(ArmResourceId id) {
        String scope = "/subscriptions/" + id.subscriptionId()
                + (id.resourceGroup() == null ? "" : "/resourceGroups/" + id.resourceGroup());
        return scope + "/providers/" + TYPE + "/" + id.names().getFirst();
    }

    private TemplateEvaluator.Scope scope(ArmResourceId id) {
        String location = id.resourceGroup() == null
                ? null
                : groups.get(id.subscriptionId(), id.resourceGroup()).map(ResourceGroup::location).orElse(null);
        return new TemplateEvaluator.Scope(directory.tenantOfSubscription(id.subscriptionId()), id.subscriptionId(),
                id.resourceGroup(), location, id.names().getFirst());
    }

    private TemplateEngine.Arm armFor(AzureRequest origin, ArmResourceId deployment) {
        return new TemplateEngine.Arm() {
            @Override
            public TemplateEngine.Result put(String resourceId, String apiVersion, Map<String, Object> body) {
                try {
                    return call("PUT", resourceId, apiVersion, MAPPER.writeValueAsBytes(body));
                } catch (JsonProcessingException e) {
                    throw new TemplateException("InvalidTemplate", "The resource '" + resourceId
                            + "' does not serialize to JSON");
                }
            }

            @Override
            public TemplateEngine.Result delete(String resourceId) {
                return call("DELETE", resourceId, null, null);
            }

            @Override
            public Map<String, Object> get(String resourceId, String apiVersion) {
                TemplateEngine.Result result = call("GET", resourceId, apiVersion, null);
                return result.succeeded() ? ArmJson.cast(result.body()) : null;
            }

            @Override
            public Object post(String resourceId, String action, String apiVersion) {
                TemplateEngine.Result result = call("POST", resourceId + "/" + action, apiVersion, null);
                return result.succeeded() ? result.body() : null;
            }

            @Override
            public List<String> resourceGroupResources() {
                return arm.get().subscriptionResources(deployment.subscriptionId()).stream()
                        .map(resource -> String.valueOf(resource.get("id")))
                        .filter(resourceId -> ArmResourceId.parse(resourceId)
                                .filter(parsed -> deployment.resourceGroup() != null
                                        && deployment.resourceGroup().equalsIgnoreCase(parsed.resourceGroup()))
                                .isPresent())
                        .toList();
            }

            private TemplateEngine.Result call(String method, String resourceId, String apiVersion, byte[] body) {
                Map<String, String> query = apiVersion == null || apiVersion.isEmpty()
                        ? Map.of()
                        : Map.of("api-version", apiVersion);
                Response response = router.get().dispatchInternal(method, resourceId, query, body, origin);
                return new TemplateEngine.Result(response.getStatus(), ArmJson.responseBody(response));
            }
        };
    }

    private static Response notFound(ArmResourceId id) {
        return ArmErrors.error(404, "DeploymentNotFound",
                "Deployment '" + id.names().getFirst() + "' could not be found.");
    }

    @Override
    public void clear() {
        deployments.clear();
    }
}
