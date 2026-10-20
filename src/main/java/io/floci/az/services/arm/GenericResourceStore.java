package io.floci.az.services.arm;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ResourceIndexContributor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resources of provider namespaces no handler claims, kept as the client sent them: the generic resource
 * operations ({@code Resources_CreateOrUpdateById}, {@code GetById}, {@code UpdateById}, {@code DeleteById},
 * {@code CheckExistenceById}) of the Resource Management API, keyed by id case-insensitively.
 *
 * <p>A {@code PUT} answers 201 when it creates and 200 when it replaces, and keeps {@link #RESOURCE_FIELDS}.
 * A {@code PATCH} replaces each of those the body carries and merges the entries of {@code properties}.
 * A nested child needs its parent, and a {@code DELETE} takes the resource's children with it.
 */
@ApplicationScoped
public class GenericResourceStore implements ResourceIndexContributor, Resettable {

    private static final Logger LOG = Logger.getLogger(GenericResourceStore.class);

    private static final List<String> RESOURCE_FIELDS = List.of("location", "tags", "properties", "managedBy",
            "kind", "sku", "plan", "identity", "zones", "extendedLocation");

    /** The fields the generic listing carries beyond {@link ArmResources#indexEntry(Map)}. */
    private static final List<String> LISTED_FIELDS = List.of("kind", "managedBy", "sku");

    private final Map<String, Map<String, Object>> resources = new ConcurrentHashMap<>();
    private final Instance<AzureServiceHandler> handlers;

    @Inject
    public GenericResourceStore(Instance<AzureServiceHandler> handlers) {
        this.handlers = handlers;
    }

    /**
     * The parsed id when {@code path} addresses a resource or collection in a provider namespace no handler
     * declares, whether or not that handler is enabled and whatever its route's guard; empty otherwise.
     */
    public Optional<ArmResourceId> unclaimed(String path, List<String> inlineNamespaces) {
        Optional<ArmResourceId> parsed = ArmResourceId.parse(path)
                .filter(id -> id.subscriptionId() != null && id.providerNamespace() != null && !id.types().isEmpty())
                .filter(id -> !id.isExtension());
        if (parsed.isEmpty()) {
            return parsed;
        }
        String namespace = parsed.get().providerNamespace();
        if (inlineNamespaces.stream().anyMatch(namespace::equalsIgnoreCase)) {
            return Optional.empty();
        }
        String marker = ("/providers/" + namespace + "/").toLowerCase(Locale.ROOT);
        for (AzureServiceHandler handler : handlers) {
            for (ServiceRoutes.ProviderRoute route : handler.routes().providers()) {
                if (route.marker().toLowerCase(Locale.ROOT).equals(marker)) {
                    return Optional.empty();
                }
            }
        }
        return parsed;
    }

    public Response handle(AzureRequest req, ArmResourceId id, String method) {
        if (id.isCollection()) {
            return "GET".equals(method) ? list(id) : notFound(id);
        }
        return switch (method) {
            case "PUT"    -> put(req, id);
            case "GET"    -> get(id);
            case "HEAD"   -> resources.containsKey(id.canonicalKey())
                    ? Response.noContent().build()
                    : Response.status(404).build();
            case "PATCH"  -> patch(req, id);
            case "DELETE" -> delete(id);
            default       -> Response.status(405).build();
        };
    }

    private synchronized Response put(AzureRequest req, ArmResourceId id) {
        ArmResourceId parent = id.parent();
        if (id.names().size() > 1 && parent != null && !resources.containsKey(parent.canonicalKey())) {
            return ArmErrors.error(404, "ParentResourceNotFound", "Failed to perform 'write' on resource(s) of type '"
                    + id.typePath() + "', because the parent resource '" + parent.id() + "' could not be found.");
        }
        Map<String, Object> existing = resources.get(id.canonicalKey());
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", existing == null ? id.id() : existing.get("id"));
        resource.put("name", id.name());
        resource.put("type", id.typePath());
        for (String field : RESOURCE_FIELDS) {
            if (body.get(field) != null) {
                resource.put(field, body.get(field));
            }
        }
        resources.put(id.canonicalKey(), resource);
        LOG.debugv("ARM: stored generic resource {0}", id.id());
        return Response.status(existing == null ? 201 : 200).entity(resource).build();
    }

    private Response get(ArmResourceId id) {
        Map<String, Object> resource = resources.get(id.canonicalKey());
        return resource == null ? notFound(id) : Response.ok(resource).build();
    }

    private synchronized Response patch(AzureRequest req, ArmResourceId id) {
        Map<String, Object> existing = resources.get(id.canonicalKey());
        if (existing == null) {
            return notFound(id);
        }
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> updated = new LinkedHashMap<>(existing);
        for (String field : RESOURCE_FIELDS) {
            Object value = body.get(field);
            if (value == null) {
                continue;
            }
            if ("properties".equals(field)) {
                Map<String, Object> properties = new LinkedHashMap<>(ArmJson.cast(existing.get(field)));
                properties.putAll(ArmJson.cast(value));
                updated.put(field, properties);
            } else {
                updated.put(field, value);
            }
        }
        resources.put(id.canonicalKey(), updated);
        return Response.ok(updated).build();
    }

    private synchronized Response delete(ArmResourceId id) {
        String key = id.canonicalKey();
        boolean existed = resources.remove(key) != null;
        resources.keySet().removeIf(other -> other.startsWith(key + "/"));
        return existed ? Response.ok().build() : Response.noContent().build();
    }

    /** The resources directly under the collection's parent, of the collection's type. */
    private Response list(ArmResourceId collection) {
        ArmResourceId parent = collection.parent();
        String type = collection.typePath();
        List<Map<String, Object>> value = new ArrayList<>();
        for (Map<String, Object> resource : resources.values()) {
            ArmResourceId id = parse(resource);
            if (id == null || !type.equalsIgnoreCase(id.typePath())) {
                continue;
            }
            boolean subscriptionWide = collection.resourceGroup() == null && collection.types().size() == 1;
            boolean matches = subscriptionWide
                    ? collection.subscriptionId().equalsIgnoreCase(id.subscriptionId())
                    : parent != null && id.parent() != null
                            && parent.canonicalKey().equals(id.parent().canonicalKey());
            if (matches) {
                value.add(resource);
            }
        }
        return Response.ok(Map.of("value", value)).build();
    }

    private static Response notFound(ArmResourceId id) {
        String where = id.resourceGroup() == null ? "" : " under resource group '" + id.resourceGroup() + "'";
        String what = id.isCollection() ? id.typePath() : id.typePath() + "/" + String.join("/", id.names());
        return ArmErrors.notFound("The Resource '" + what + "'" + where + " was not found.");
    }

    private static ArmResourceId parse(Map<String, Object> resource) {
        return resource.get("id") instanceof String text ? ArmResourceId.parse(text).orElse(null) : null;
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return listed(sub, rg);
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return listed(sub, null);
    }

    /** The scope's top-level resources as {@code /resources} entries. */
    private List<Map<String, Object>> listed(String sub, String rg) {
        List<Map<String, Object>> listed = new ArrayList<>();
        for (Map<String, Object> resource : resources.values()) {
            ArmResourceId id = parse(resource);
            if (id == null || id.names().size() != 1 || !ArmResources.inScope(resource.get("id"), sub, rg)) {
                continue;
            }
            Map<String, Object> entry = ArmResources.indexEntry(resource);
            for (String field : LISTED_FIELDS) {
                if (resource.get(field) != null) {
                    entry.put(field, resource.get(field));
                }
            }
            listed.add(entry);
        }
        return listed;
    }

    @Override
    public void clear() {
        resources.clear();
    }
}
