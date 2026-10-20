package io.floci.az.services.deployments;

import io.floci.az.core.arm.ArmResourceId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs one template deployment: binds the parameters, expands the {@code resources} (nested child resources,
 * {@code copy} loops, {@code condition}, extension {@code scope}, symbolic names, {@code existing} references),
 * orders them by {@code dependsOn}, provisions each through {@link Arm}, and evaluates the {@code outputs}.
 * Provisioning stops at the first resource that fails.
 */
final class TemplateEngine {

    /** Requests to the emulator's own ARM handlers. */
    interface Arm extends TemplateEvaluator.Resources {
        /** {@code PUT {id}?api-version} with {@code body}. */
        Result put(String id, String apiVersion, Map<String, Object> body);

        /** {@code DELETE {id}}. */
        Result delete(String id);

        /** The ids of the top-level resources in the deployment's resource group. */
        List<String> resourceGroupResources();
    }

    record Result(int status, Object body) {
        boolean succeeded() {
            return status / 100 == 2;
        }
    }

    /** One provisioning step, as the deployment operations list reports it. */
    record Operation(String resourceId, String resourceType, String resourceName, String provisioningOperation,
                     Result result) {
    }

    /** The result of a deployment, with the error it ended on when it failed. */
    record Outcome(Map<String, Object> parameters, List<Operation> operations, Map<String, Object> outputs,
                   List<String> outputResources, TemplateException error, Operation failed) {
    }

    private static final Set<String> NON_BODY_FIELDS = Set.of("type", "name", "apiversion", "dependson", "copy",
            "condition", "resources", "comments", "scope", "existing", "import", "metadata");
    private static final String RESOURCE_GROUP_TYPE = "Microsoft.Resources/resourceGroups";

    private record Planned(String symbol, String copyName, String id, String type, String name, String apiVersion,
                           Map<String, Object> raw, TemplateEvaluator evaluator, List<String> dependsOn,
                           boolean existing) {
    }

    private final TemplateEvaluator.Scope scope;
    private final Arm arm;

    TemplateEngine(TemplateEvaluator.Scope scope, Arm arm) {
        this.scope = scope;
        this.arm = arm;
    }

    /** Binds each {@code given} parameter's {@code value} to the template's parameters, throwing on a mismatch. */
    Map<String, Object> bindParameters(Map<String, Object> template, Map<String, Object> given) {
        Map<String, Object> declared = optionalMap(template.get("parameters"));
        for (String name : given.keySet()) {
            if (declared.keySet().stream().noneMatch(name::equalsIgnoreCase)) {
                throw new TemplateException("InvalidTemplate", "The template parameter '" + name
                        + "' is given a value but is not declared in the template.");
            }
        }
        Map<String, Object> bound = new LinkedHashMap<>();
        TemplateEvaluator defaults = new TemplateEvaluator(scope, bound, Map.of(), arm);
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            Map<String, Object> spec = optionalMap(entry.getValue());
            Object supplied = given.entrySet().stream()
                    .filter(g -> g.getKey().equalsIgnoreCase(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            Object value;
            if (supplied instanceof Map<?, ?> wrapper && wrapper.containsKey("value")) {
                value = wrapper.get("value");
            } else if (supplied instanceof Map<?, ?> wrapper && wrapper.containsKey("reference")) {
                throw new TemplateException("InvalidTemplate", "The template parameter '" + entry.getKey()
                        + "' uses a Key Vault reference, which floci-az does not resolve.");
            } else if (spec.containsKey("defaultValue")) {
                value = defaults.evaluate(spec.get("defaultValue"));
            } else {
                throw new TemplateException("InvalidTemplate", "Deployment template validation failed: "
                        + "'The value for the template parameter '" + entry.getKey() + "' is not provided.'");
            }
            if (spec.get("allowedValues") instanceof List<?> allowed && !allowed.contains(value)) {
                throw new TemplateException("InvalidTemplate", "Deployment template validation failed: 'The "
                        + "provided value '" + value + "' for the template parameter '" + entry.getKey()
                        + "' is not valid. The parameter value is not part of the allowed value(s): '"
                        + String.join(",", allowed.stream().map(String::valueOf).toList()) + "'.'");
            }
            bound.put(entry.getKey(), value);
        }
        return bound;
    }

    /** The ids the template would deploy, in order, without deploying anything. */
    List<String> validate(Map<String, Object> template, Map<String, Object> parameters) {
        return order(plan(template, parameters)).stream().filter(p -> !p.existing()).map(Planned::id).toList();
    }

    Outcome deploy(Map<String, Object> template, Map<String, Object> parameters, boolean complete) {
        List<Operation> operations = new ArrayList<>();
        List<String> deployed = new ArrayList<>();
        List<Planned> ordered;
        TemplateEvaluator root;
        try {
            root = new TemplateEvaluator(scope, parameters, optionalMap(template.get("variables")), arm);
            ordered = order(plan(template, parameters));
        } catch (TemplateException e) {
            return new Outcome(parameters, operations, Map.of(), deployed, e, null);
        }
        for (Planned planned : ordered) {
            if (planned.existing()) {
                continue;
            }
            Result result;
            try {
                result = arm.put(planned.id(), planned.apiVersion(), body(planned));
            } catch (TemplateException e) {
                return new Outcome(parameters, operations, Map.of(), deployed, e, null);
            }
            Operation operation = new Operation(planned.id(), planned.type(), planned.name(), "Create", result);
            operations.add(operation);
            if (!result.succeeded()) {
                return new Outcome(parameters, operations, Map.of(), deployed, null, operation);
            }
            deployed.add(planned.id());
        }
        if (complete) {
            Set<String> kept = new LinkedHashSet<>(deployed.stream().map(id -> id.toLowerCase(Locale.ROOT)).toList());
            for (String id : arm.resourceGroupResources()) {
                if (!kept.contains(id.toLowerCase(Locale.ROOT))) {
                    ArmResourceId parsed = ArmResourceId.parse(id).orElse(null);
                    operations.add(new Operation(id, parsed == null ? "" : parsed.typePath(),
                            parsed == null ? id : parsed.name(), "Delete", arm.delete(id)));
                }
            }
        }
        try {
            return new Outcome(parameters, operations, outputs(root, optionalMap(template.get("outputs"))),
                    deployed, null, null);
        } catch (TemplateException e) {
            return new Outcome(parameters, operations, Map.of(), deployed, e, null);
        }
    }

    private Map<String, Object> outputs(TemplateEvaluator root, Map<String, Object> declared) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            Map<String, Object> spec = optionalMap(entry.getValue());
            if (spec.containsKey("condition") && !root.truthy(spec.get("condition"))) {
                continue;
            }
            Object value;
            if (spec.get("copy") instanceof Map<?, ?> loop) {
                int count = TemplateEvaluator.toInt(root.evaluate(loop.get("count")));
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    values.add(root.inCopy(entry.getKey(), i).evaluate(loop.get("input")));
                }
                value = values;
            } else {
                value = root.evaluate(spec.get("value"));
            }
            outputs.put(entry.getKey(), Map.of("type", String.valueOf(spec.getOrDefault("type", "String")),
                    "value", value == null ? "" : value));
        }
        return outputs;
    }

    private Map<String, Object> body(Planned planned) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : planned.raw().entrySet()) {
            if (!NON_BODY_FIELDS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                body.put(entry.getKey(), planned.evaluator().evaluate(entry.getValue()));
            }
        }
        return body;
    }

    // ── Planning ────────────────────────────────────────────────────────────────

    private List<Planned> plan(Map<String, Object> template, Map<String, Object> parameters) {
        TemplateEvaluator root = new TemplateEvaluator(scope, parameters, optionalMap(template.get("variables")), arm);
        List<Planned> planned = new ArrayList<>();
        Object resources = template.get("resources");
        if (resources instanceof Map<?, ?> symbolic) {
            for (Map.Entry<?, ?> entry : symbolic.entrySet()) {
                expand(String.valueOf(entry.getKey()), TemplateEvaluator.map(entry.getValue(), "A resource"), null,
                        root, planned);
            }
        } else if (resources instanceof List<?> list) {
            for (Object resource : list) {
                expand(null, TemplateEvaluator.map(resource, "A resource"), null, root, planned);
            }
        }
        return planned;
    }

    private void expand(String symbol, Map<String, Object> resource, Planned parent, TemplateEvaluator evaluator,
                        List<Planned> out) {
        if (resource.get("copy") instanceof Map<?, ?> loop) {
            String copyName = String.valueOf(loop.get("name"));
            int count = TemplateEvaluator.toInt(evaluator.evaluate(loop.get("count")));
            for (int i = 0; i < count; i++) {
                expandOne(symbol, copyName, resource, parent, evaluator.inCopy(copyName, i), out);
            }
            return;
        }
        expandOne(symbol, null, resource, parent, evaluator, out);
    }

    private void expandOne(String symbol, String copyName, Map<String, Object> resource, Planned parent,
                           TemplateEvaluator evaluator, List<Planned> out) {
        if (resource.containsKey("condition") && !evaluator.truthy(resource.get("condition"))) {
            return;
        }
        String type = TemplateEvaluator.text(evaluator.evaluate(resource.get("type")));
        String name = TemplateEvaluator.text(evaluator.evaluate(resource.get("name")));
        if (type.isEmpty() || name.isEmpty()) {
            throw new TemplateException("InvalidTemplate", "Every template resource needs a type and a name");
        }
        if (type.equalsIgnoreCase("Microsoft.Resources/deployments")) {
            throw new TemplateException("InvalidTemplate",
                    "Nested deployments (Microsoft.Resources/deployments) are not supported by floci-az");
        }
        if (parent != null && !type.contains("/")) {
            type = parent.type() + "/" + type;
            name = parent.name() + "/" + name;
        }
        String id = id(type, name, resource.get("scope") == null
                ? null
                : TemplateEvaluator.text(evaluator.evaluate(resource.get("scope"))));
        List<String> dependsOn = new ArrayList<>();
        if (resource.get("dependsOn") instanceof List<?> deps) {
            deps.forEach(dep -> dependsOn.add(TemplateEvaluator.text(evaluator.evaluate(dep))));
        }
        if (parent != null) {
            dependsOn.add(parent.id());
        }
        Planned planned = new Planned(symbol, copyName, id, type, name,
                TemplateEvaluator.text(evaluator.evaluate(resource.get("apiVersion"))), resource, evaluator,
                dependsOn, TemplateEvaluator.truthyValue(resource.get("existing")));
        out.add(planned);
        if (resource.get("resources") instanceof List<?> children) {
            for (Object child : children) {
                expand(null, TemplateEvaluator.map(child, "A child resource"), planned, evaluator, out);
            }
        }
    }

    private String id(String type, String name, String extensionScope) {
        if (type.equalsIgnoreCase(RESOURCE_GROUP_TYPE)) {
            return "/subscriptions/" + scope.subscriptionId() + "/resourceGroups/" + name;
        }
        List<Object> parts = new ArrayList<>();
        parts.add(type);
        parts.addAll(List.of((Object[]) name.split("/")));
        String path = TemplateEvaluator.typeAndNames(parts);
        if (extensionScope != null) {
            return (extensionScope.endsWith("/") ? extensionScope.substring(0, extensionScope.length() - 1)
                    : extensionScope) + "/providers/" + path;
        }
        String base = "/subscriptions/" + scope.subscriptionId();
        if (scope.resourceGroup() != null) {
            base += "/resourceGroups/" + scope.resourceGroup();
        }
        return base + "/providers/" + path;
    }

    // ── Ordering ────────────────────────────────────────────────────────────────

    /** The resources in an order where each follows what it depends on, keeping template order otherwise. */
    private static List<Planned> order(List<Planned> planned) {
        Map<Planned, Set<Planned>> needs = new LinkedHashMap<>();
        for (Planned resource : planned) {
            Set<Planned> targets = new LinkedHashSet<>();
            for (String dependency : resource.dependsOn()) {
                List<Planned> matched = planned.stream().filter(other -> other != resource
                        && refersTo(dependency, other)).toList();
                targets.addAll(matched);
            }
            needs.put(resource, targets);
        }
        List<Planned> ordered = new ArrayList<>();
        Set<Planned> done = new LinkedHashSet<>();
        while (ordered.size() < planned.size()) {
            Planned next = needs.entrySet().stream()
                    .filter(entry -> !done.contains(entry.getKey()) && done.containsAll(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow(() -> new TemplateException("InvalidTemplate", "Deployment template validation "
                            + "failed: 'Circular dependency detected on resource: '" + needs.keySet().stream()
                            .filter(p -> !done.contains(p)).findFirst().map(Planned::id).orElse("") + "'.'"));
            ordered.add(next);
            done.add(next);
        }
        return ordered;
    }

    /** Whether a {@code dependsOn} entry names {@code resource}: by id, type and name, name, symbol or copy loop. */
    private static boolean refersTo(String dependency, Planned resource) {
        String dep = dependency.toLowerCase(Locale.ROOT);
        String id = resource.id().toLowerCase(Locale.ROOT);
        if (dep.equals(id) || id.endsWith("/providers/" + dep) || id.endsWith("/" + dep) && dep.contains("/")) {
            return true;
        }
        if (dep.equalsIgnoreCase(resource.name()) || dep.equalsIgnoreCase(resource.symbol())) {
            return true;
        }
        return resource.copyName() != null && dep.equalsIgnoreCase(resource.copyName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionalMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
