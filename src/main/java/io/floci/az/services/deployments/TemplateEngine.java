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
 *
 * <p>A nested deployment ({@code Microsoft.Resources/deployments}) is provisioned like any other resource: a
 * {@code PUT} of the deployment at the scope its {@code subscriptionId} and {@code resourceGroup} name, which runs
 * its template there. With {@code expressionEvaluationOptions.scope} {@code inner} its {@code parameters} are
 * evaluated here and its template there. With the default {@code outer}, the nested template is handed the
 * parent's parameters and variables, and is evaluated at the target scope.
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
    private static final String DEPLOYMENT_TYPE = "Microsoft.Resources/deployments";
    private static final Set<String> TARGET_FIELDS = Set.of("subscriptionid", "resourcegroup");

    private record Planned(String symbol, String copyName, String id, String type, String name, String apiVersion,
                           Map<String, Object> raw, TemplateEvaluator evaluator, List<String> dependsOn,
                           boolean existing) {
    }

    private final TemplateEvaluator.Scope scope;
    private final Arm arm;

    /** The template being deployed and its bound parameters, which an {@code outer} nested deployment inherits. */
    private Map<String, Object> template = Map.of();
    private Map<String, Object> parameters = Map.of();

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
        this.template = template;
        this.parameters = parameters;
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
            boolean nested = isDeployment(planned.type());
            if (nested && result.succeeded()) {
                result = nestedResult(result);
            }
            Operation operation = new Operation(planned.id(), planned.type(), planned.name(), "Create", result);
            operations.add(operation);
            if (!result.succeeded()) {
                return new Outcome(parameters, operations, Map.of(), deployed, null, operation);
            }
            deployed.add(planned.id());
            if (nested) {
                deployed.addAll(nestedResources(result));
            }
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
        if (isDeployment(planned.type())) {
            return nestedBody(planned);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : planned.raw().entrySet()) {
            if (!NON_BODY_FIELDS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                body.put(entry.getKey(), planned.evaluator().evaluate(entry.getValue()));
            }
        }
        return body;
    }

    // ── Nested deployments ──────────────────────────────────────────────────────

    private static boolean isDeployment(String type) {
        return DEPLOYMENT_TYPE.equalsIgnoreCase(type);
    }

    /**
     * The nested deployment's body: its fields evaluated here, except {@code properties.template}, which runs at
     * the target. An {@code outer} template gets the parent's parameters and variables to evaluate against.
     */
    private Map<String, Object> nestedBody(Planned planned) {
        TemplateEvaluator evaluator = planned.evaluator();
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> rawProperties = Map.of();
        for (Map.Entry<String, Object> entry : planned.raw().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.equals("properties")) {
                rawProperties = optionalMap(entry.getValue());
            } else if (!NON_BODY_FIELDS.contains(key) && !TARGET_FIELDS.contains(key)) {
                body.put(entry.getKey(), evaluator.evaluate(entry.getValue()));
            }
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        Object nestedTemplate = null;
        Object nestedParameters = null;
        for (Map.Entry<String, Object> entry : rawProperties.entrySet()) {
            switch (entry.getKey().toLowerCase(Locale.ROOT)) {
                case "template" -> nestedTemplate = entry.getValue();
                case "parameters" -> nestedParameters = entry.getValue();
                default -> properties.put(entry.getKey(), evaluator.evaluate(entry.getValue()));
            }
        }
        boolean inner = "inner".equalsIgnoreCase(String.valueOf(
                optionalMap(properties.get("expressionEvaluationOptions")).get("scope")));
        if (inner) {
            if (nestedTemplate != null) {
                properties.put("template", nestedTemplate);
            }
            if (nestedParameters != null) {
                properties.put("parameters", evaluator.evaluate(nestedParameters));
            }
        } else if (nestedTemplate != null) {
            properties.put("template", outerTemplate(optionalMap(nestedTemplate)));
            Map<String, Object> values = new LinkedHashMap<>();
            parameters.forEach((name, value) -> values.put(name, Map.of("value", value)));
            properties.put("parameters", values);
        }
        body.put("properties", properties);
        return body;
    }

    /** The nested template with the parent's parameter declarations and variables added to its own. */
    private Map<String, Object> outerTemplate(Map<String, Object> nested) {
        Map<String, Object> out = new LinkedHashMap<>(nested);
        Map<String, Object> declared = new LinkedHashMap<>(optionalMap(template.get("parameters")));
        declared.putAll(optionalMap(nested.get("parameters")));
        out.put("parameters", declared);
        Map<String, Object> variables = new LinkedHashMap<>(optionalMap(template.get("variables")));
        variables.putAll(optionalMap(nested.get("variables")));
        out.put("variables", variables);
        return out;
    }

    /** A nested deployment's answer, as a failure carrying its error when its deployment ended {@code Failed}. */
    private static Result nestedResult(Result result) {
        Map<String, Object> properties = optionalMap(optionalMap(result.body()).get("properties"));
        if (!"Failed".equalsIgnoreCase(String.valueOf(properties.get("provisioningState")))) {
            return result;
        }
        Object error = properties.get("error") == null
                ? Map.of("code", "DeploymentFailed", "message", "The nested deployment failed.")
                : properties.get("error");
        return new Result(409, Map.of("error", error));
    }

    /** The resources a nested deployment reports in its {@code outputResources}. */
    private static List<String> nestedResources(Result result) {
        Map<String, Object> properties = optionalMap(optionalMap(result.body()).get("properties"));
        List<String> ids = new ArrayList<>();
        if (properties.get("outputResources") instanceof List<?> resources) {
            for (Object resource : resources) {
                if (optionalMap(resource).get("id") instanceof String id) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * The nested deployment's id at its target: the {@code subscriptionId} and {@code resourceGroup} it names, a
     * {@code scope} naming a subscription or resource group, or else this deployment's own scope.
     */
    private String deploymentId(String name, Map<String, Object> resource, TemplateEvaluator evaluator) {
        String subscription = scope.subscriptionId();
        String group = scope.resourceGroup();
        if (resource.get("scope") != null) {
            ArmResourceId target = ArmResourceId.parse(TemplateEvaluator.text(evaluator.evaluate(resource.get("scope"))))
                    .filter(id -> id.subscriptionId() != null && id.providerNamespace() == null)
                    .orElseThrow(() -> new TemplateException("InvalidTemplate", "The nested deployment '" + name
                            + "' has a scope floci-az does not deploy to; use a subscription or resource group."));
            subscription = target.subscriptionId();
            group = target.resourceGroup();
        }
        String givenSubscription = TemplateEvaluator.text(evaluator.evaluate(resource.get("subscriptionId")));
        String givenGroup = TemplateEvaluator.text(evaluator.evaluate(resource.get("resourceGroup")));
        if (!givenSubscription.isEmpty()) {
            subscription = givenSubscription;
            group = null;
        }
        if (!givenGroup.isEmpty()) {
            group = givenGroup;
        }
        return "/subscriptions/" + subscription + (group == null ? "" : "/resourceGroups/" + group)
                + "/providers/" + DEPLOYMENT_TYPE + "/" + name;
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
        if (parent != null && !type.contains("/")) {
            type = parent.type() + "/" + type;
            name = parent.name() + "/" + name;
        }
        String id;
        if (isDeployment(type)) {
            id = deploymentId(name, resource, evaluator);
        } else {
            id = id(type, name, resource.get("scope") == null
                    ? null
                    : TemplateEvaluator.text(evaluator.evaluate(resource.get("scope"))));
        }
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
