package io.floci.az.services.tags;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.SubscriptionTags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Tags API: {@code {scope}/providers/Microsoft.Resources/tags/default} at a subscription, a resource group or
 * any resource. {@code GET} reads the scope's tags, {@code PUT} replaces them, {@code PATCH} applies a
 * {@code Merge}, {@code Replace} or {@code Delete} operation, and {@code DELETE} removes them all. Shapes follow
 * the resources spec {@code 2021-04-01}; any {@code api-version} is accepted.
 *
 * <p>A resource group or resource keeps its tags where its own handler keeps them: this handler reads the target
 * with an internal {@code GET} and writes its new tag set with an internal {@code PATCH} of {@code tags}, falling
 * back to a {@code PUT} of the resource for a handler that answers no {@code PATCH}. The target's reads and
 * Resource Graph then report the result. Subscription tags live in {@link SubscriptionTags}.
 *
 * <p>The router sends these paths here ahead of the provider routes, since the tags of a resource sit under that
 * resource's own provider path.
 */
@ApplicationScoped
public class TagsHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(TagsHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SERVICE_TYPE = "tags";
    private static final String NAMESPACE = "Microsoft.Resources";

    private final EmulatorConfig config;
    private final Instance<ArmRouter> router;
    private final AzureDirectory directory;
    private final SubscriptionTags subscriptionTags;

    @Inject
    public TagsHandler(EmulatorConfig config, Instance<ArmRouter> router, AzureDirectory directory,
                       SubscriptionTags subscriptionTags) {
        this.config = config;
        this.router = router;
        this.directory = directory;
        this.subscriptionTags = subscriptionTags;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().tags().enabled();
    }

    /** No provider route: {@code Microsoft.Resources} is the deployments route, and a router stage sends tags here. */
    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder().build();
    }

    /** The scope the path's tags resource sits on, when the path is {@code {scope}/providers/Microsoft.Resources/tags/default}. */
    public static Optional<ArmResourceId> tagsScope(String path) {
        return ArmResourceId.parse(path)
                .filter(id -> NAMESPACE.equalsIgnoreCase(id.providerNamespace())
                        && id.types().size() == 1 && id.types().getFirst().equalsIgnoreCase("tags")
                        && id.names().size() == 1 && id.names().getFirst().equalsIgnoreCase("default")
                        && id.scope() != null && id.subscriptionId() != null);
    }

    public static boolean matches(String path) {
        return tagsScope(path).isPresent();
    }

    @Override
    public Response handle(AzureRequest req) {
        Optional<ArmResourceId> parsed = tagsScope(req.resourcePath());
        if (parsed.isEmpty()) {
            return ArmErrors.notFound("Resource not found: " + req.resourcePath());
        }
        ArmResourceId scope = parsed.get().scope();
        String method = req.method().toUpperCase(Locale.ROOT);
        try {
            return switch (method) {
                case "GET" -> read(req, scope).map(tags -> answer(scope, tags)).orElseGet(() -> targetNotFound(scope));
                case "PUT" -> write(req, scope, replace(ArmJson.parseBodyStrict(req)));
                case "PATCH" -> patch(req, scope, ArmJson.parseBodyStrict(req));
                case "DELETE" -> write(req, scope, current -> Map.of());
                default -> Response.status(405).build();
            };
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
    }

    private interface TagChange {
        Map<String, String> apply(Map<String, String> current);
    }

    private static TagChange replace(Map<String, Object> body) {
        Map<String, String> tags = tags(ArmJson.cast(body.get("properties")).get("tags"));
        return current -> tags;
    }

    private Response patch(AzureRequest req, ArmResourceId scope, Map<String, Object> body) {
        Map<String, String> given = tags(ArmJson.cast(body.get("properties")).get("tags"));
        String operation = String.valueOf(body.get("operation"));
        TagChange change = switch (operation.toLowerCase(Locale.ROOT)) {
            case "merge" -> current -> merge(current, given);
            case "replace" -> current -> given;
            case "delete" -> current -> delete(current, given);
            default -> null;
        };
        if (change == null) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The tags operation '" + body.get("operation") + "' is not valid; use Merge, Replace or Delete.");
        }
        return write(req, scope, change);
    }

    /** {@code current} with each of {@code given} set, a name in another spelling taking the new one. */
    static Map<String, String> merge(Map<String, String> current, Map<String, String> given) {
        Map<String, String> merged = new LinkedHashMap<>(current);
        given.forEach((name, value) -> {
            merged.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            merged.put(name, value);
        });
        return merged;
    }

    /** {@code current} without each of {@code given}: by name, and by value too when the given value is not empty. */
    static Map<String, String> delete(Map<String, String> current, Map<String, String> given) {
        Map<String, String> kept = new LinkedHashMap<>(current);
        given.forEach((name, value) -> kept.entrySet().removeIf(entry -> entry.getKey().equalsIgnoreCase(name)
                && (value.isEmpty() || value.equals(entry.getValue()))));
        return kept;
    }

    private Response write(AzureRequest req, ArmResourceId scope, TagChange change) {
        if (isSubscription(scope)) {
            if (directory.subscription(scope.subscriptionId()).isEmpty()) {
                return ArmErrors.subscriptionNotFound(scope.subscriptionId());
            }
            Map<String, String> tags = change.apply(subscriptionTags.get(scope.subscriptionId()));
            subscriptionTags.put(scope.subscriptionId(), tags);
            return answer(scope, tags);
        }
        Optional<Map<String, Object>> target = get(req, scope);
        if (target.isEmpty()) {
            return targetNotFound(scope);
        }
        Map<String, String> tags = change.apply(tags(target.get().get("tags")));
        Response written = writeTags(req, scope, target.get(), tags);
        if (written.getStatus() >= 300) {
            return written;
        }
        LOG.debugv("ARM: tags of {0} set to {1}", scope.id(), tags);
        return answer(scope, read(req, scope).orElse(tags));
    }

    /** Writes {@code tags} as the target's whole tag set: a {@code PATCH}, or a {@code PUT} where none is served. */
    private Response writeTags(AzureRequest req, ArmResourceId scope, Map<String, Object> target,
                               Map<String, String> tags) {
        Response patched = dispatch(req, "PATCH", scope, Map.of("tags", tags));
        int status = patched.getStatus();
        if (status < 300 || status != 404 && status != 405 && status != 400) {
            return patched;
        }
        Map<String, Object> body = new LinkedHashMap<>(target);
        body.put("tags", tags);
        return dispatch(req, "PUT", scope, body);
    }

    private Optional<Map<String, String>> read(AzureRequest req, ArmResourceId scope) {
        if (isSubscription(scope)) {
            return directory.subscription(scope.subscriptionId()).map(s -> subscriptionTags.get(s.id()));
        }
        return get(req, scope).map(target -> tags(target.get("tags")));
    }

    private Optional<Map<String, Object>> get(AzureRequest req, ArmResourceId scope) {
        Response response = dispatch(req, "GET", scope, null);
        return response.getStatus() == 200 ? Optional.of(ArmJson.cast(ArmJson.responseBody(response))) : Optional.empty();
    }

    private Response dispatch(AzureRequest req, String method, ArmResourceId scope, Map<String, Object> body) {
        String apiVersion = req.queryParams() == null ? null : req.queryParams().get("api-version");
        Map<String, String> query = apiVersion == null ? Map.of() : Map.of("api-version", apiVersion);
        byte[] bytes = null;
        if (body != null) {
            try {
                bytes = MAPPER.writeValueAsBytes(body);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("tags do not serialize to JSON", e);
            }
        }
        return router.get().dispatchInternal(method, scope.id(), query, bytes, req);
    }

    private static boolean isSubscription(ArmResourceId scope) {
        return scope.resourceGroup() == null && scope.providerNamespace() == null;
    }

    private static Map<String, String> tags(Object raw) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((name, value) -> tags.put(String.valueOf(name), value == null ? "" : String.valueOf(value)));
        }
        return tags;
    }

    private static Response answer(ArmResourceId scope, Map<String, String> tags) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", scope.id() + "/providers/" + NAMESPACE + "/tags/default");
        body.put("name", "default");
        body.put("type", NAMESPACE + "/tags");
        body.put("properties", Map.of("tags", tags));
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    private static Response targetNotFound(ArmResourceId scope) {
        return scope.providerNamespace() == null
                ? ArmErrors.resourceGroupNotFound(scope.resourceGroup())
                : ArmErrors.notFound("The Resource '" + scope.id() + "' was not found.");
    }
}
