package io.floci.az.services.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmRequestRule;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmVerdict;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import io.floci.az.services.arm.ArmHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The gate rule that applies policy assignments to ARM writes under {@code floci-az.services.policy.evaluate}:
 * the {@code deny} effect on {@code PUT} and {@code PATCH}, and {@code denyAction} with the {@code delete}
 * action on {@code DELETE}. A request one of them matches is refused with {@code 403 RequestDisallowedByPolicy}
 * before its handler runs.
 *
 * <p>An assignment applies when its scope covers the target, no {@code notScopes} entry does, its
 * {@code enforcementMode} is {@code Default}, and no unexpired exemption covers it. A {@code PATCH} is judged on
 * the resource it would leave: the indexed resource with the body's fields over it. A {@code DELETE} is judged on
 * the indexed resource. {@code Indexed} definitions skip resource groups, as Azure's do.
 *
 * <p>A rule the {@link PolicyEvaluator} cannot read, or a definition floci-az does not hold (built-ins are not
 * seeded), refuses the request too: the rule fails closed. Effects other than the two above are never
 * evaluated, since they would not block the request.
 */
@ApplicationScoped
public class PolicyEnforcementRule implements ArmRequestRule {

    private static final Logger LOG = Logger.getLogger(PolicyEnforcementRule.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Position in the gate: after the RBAC rule, as Azure checks policy once access is granted. */
    public static final int ORDER = 3;

    private static final String RESOURCE_GROUP_TYPE = "Microsoft.Resources/subscriptions/resourceGroups";
    private static final String EXEMPTIONS_MARKER = "/providers/microsoft.authorization/policyexemptions/";

    private final PolicyStore store;
    private final ResourceGroups groups;
    private final Instance<ArmHandler> arm;
    private final boolean evaluate;

    @Inject
    public PolicyEnforcementRule(PolicyStore store, ResourceGroups groups, Instance<ArmHandler> arm,
                                 EmulatorConfig config) {
        this.store = store;
        this.groups = groups;
        this.arm = arm;
        this.evaluate = config.services().policy().enabled() && config.services().policy().evaluate();
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (!evaluate || call.id() == null || call.id().subscriptionId() == null || call.id().isCollection()) {
            return ArmVerdict.proceed();
        }
        boolean write = "PUT".equals(call.method()) || "PATCH".equals(call.method());
        boolean delete = "DELETE".equals(call.method());
        Optional<String> type = targetType(call.id());
        if (!write && !delete || type.isEmpty()) {
            return ArmVerdict.proceed();
        }
        Map<String, Object> resource = resource(call, type.get());
        for (Map<String, Object> assignment : store.listAssignments()) {
            Optional<Response> refusal = apply(assignment, resource, write);
            if (refusal.isPresent()) {
                return ArmVerdict.respond(refusal.get());
            }
        }
        return ArmVerdict.proceed();
    }

    /** The type a policy sees for the target, or empty for a path policy does not govern. */
    private static Optional<String> targetType(ArmResourceId id) {
        if (id.providerNamespace() == null) {
            return id.resourceGroup() != null && id.types().isEmpty()
                    ? Optional.of(RESOURCE_GROUP_TYPE)
                    : Optional.empty();
        }
        String type = id.typePath();
        if (type == null || type.toLowerCase(Locale.ROOT).startsWith("microsoft.authorization/policy")) {
            return Optional.empty();
        }
        return Optional.of(type);
    }

    /** The resource the request would leave, or the one it deletes, as the evaluator reads it. */
    private Map<String, Object> resource(ArmCall call, String type) {
        ArmResourceId id = call.id();
        Map<String, Object> resource = new LinkedHashMap<>();
        if (!"PUT".equals(call.method())) {
            existing(id, type).ifPresent(resource::putAll);
        }
        if (call.body() != null) {
            for (String field : List.of("location", "tags", "kind")) {
                if (call.body().get(field) != null) {
                    resource.put(field, call.body().get(field));
                }
            }
        }
        boolean group = RESOURCE_GROUP_TYPE.equals(type);
        resource.put("id", id.id());
        resource.put("name", group ? id.resourceGroup() : id.name());
        resource.put("fullName", group ? id.resourceGroup() : String.join("/", id.names()));
        resource.put("type", type);
        return resource;
    }

    private Optional<Map<String, Object>> existing(ArmResourceId id, String type) {
        if (RESOURCE_GROUP_TYPE.equals(type)) {
            return groups.get(id.subscriptionId(), id.resourceGroup()).map(ResourceGroup::body);
        }
        return arm.get().subscriptionResources(id.subscriptionId()).stream()
                .filter(entry -> id.id().equalsIgnoreCase(String.valueOf(entry.get("id"))))
                .findFirst();
    }

    /** The refusal {@code assignment} makes of the request, or empty when it lets the request through. */
    private Optional<Response> apply(Map<String, Object> assignment, Map<String, Object> resource, boolean write) {
        Map<String, Object> properties = PolicyHandler.propertiesOf(assignment);
        String target = String.valueOf(resource.get("id"));
        if (!covers(String.valueOf(properties.get("scope")), target)
                || "DoNotEnforce".equalsIgnoreCase(String.valueOf(properties.get("enforcementMode")))
                || strings(properties.get("notScopes")).stream().anyMatch(scope -> covers(scope, target))) {
            return Optional.empty();
        }
        String assignmentId = String.valueOf(assignment.get("id"));
        List<String> exemptReferences = new ArrayList<>();
        for (Map<String, Object> exemption : store.listExemptions()) {
            Map<String, Object> exempt = PolicyHandler.propertiesOf(exemption);
            if (assignmentId.equalsIgnoreCase(String.valueOf(exempt.get("policyAssignmentId")))
                    && covers(exemptionScope(String.valueOf(exemption.get("id"))), target) && !expired(exempt)) {
                List<String> references = strings(exempt.get("policyDefinitionReferenceIds"));
                if (references.isEmpty()) {
                    return Optional.empty();
                }
                exemptReferences.addAll(references);
            }
        }
        Map<String, Object> assigned = values(properties.get("parameters"));
        String definitionId = String.valueOf(properties.get("policyDefinitionId"));
        try {
            if (definitionId.toLowerCase(Locale.ROOT).contains("/policysetdefinitions/")) {
                return applySet(assignment, definitionId, assigned, exemptReferences, resource, write);
            }
            return judge(assignment, definitionId, null, assigned, resource, write);
        } catch (PolicyEvaluator.NotEvaluable e) {
            LOG.infov("Policy: assignment {0} cannot be evaluated, refusing: {1}", assignmentId, e.getMessage());
            return Optional.of(refusal(assignment, definitionId, resource, write,
                    "floci-az cannot evaluate this policy, so it refuses the request: " + e.getMessage()));
        }
    }

    private Optional<Response> applySet(Map<String, Object> assignment, String setId, Map<String, Object> assigned,
                                        List<String> exemptReferences, Map<String, Object> resource, boolean write) {
        Map<String, Object> set = store.getSetDefinition(setId);
        if (set == null) {
            throw new PolicyEvaluator.NotEvaluable("the policy set definition '" + setId + "' is not held");
        }
        Map<String, Object> setProperties = PolicyHandler.propertiesOf(set);
        Map<String, Object> setParameters = withDefaults(setProperties.get("parameters"), assigned);
        PolicyEvaluator setScope = new PolicyEvaluator(setParameters, resource);
        for (Object raw : listOf(setProperties.get("policyDefinitions"))) {
            if (!(raw instanceof Map<?, ?> reference)) {
                continue;
            }
            String referenceId = String.valueOf(reference.get("policyDefinitionReferenceId"));
            if (exemptReferences.stream().anyMatch(referenceId::equalsIgnoreCase)) {
                continue;
            }
            Map<String, Object> referenceValues = new LinkedHashMap<>();
            values(reference.get("parameters")).forEach((name, value) -> referenceValues.put(name, setScope.resolve(value)));
            Optional<Response> refusal = judge(assignment, String.valueOf(reference.get("policyDefinitionId")),
                    referenceId, referenceValues, resource, write);
            if (refusal.isPresent()) {
                return refusal;
            }
        }
        return Optional.empty();
    }

    /** Whether one definition refuses the request, given the values its parameters receive. */
    private Optional<Response> judge(Map<String, Object> assignment, String definitionId, String referenceId,
                                     Map<String, Object> assigned, Map<String, Object> resource, boolean write) {
        Map<String, Object> definition = store.getDefinition(definitionId);
        if (definition == null) {
            throw new PolicyEvaluator.NotEvaluable("the policy definition '" + definitionId + "' is not held");
        }
        Map<String, Object> properties = PolicyHandler.propertiesOf(definition);
        String mode = String.valueOf(properties.getOrDefault("mode", "Indexed"));
        if (!mode.equalsIgnoreCase("All") && !mode.equalsIgnoreCase("Indexed")) {
            return Optional.empty();
        }
        if (mode.equalsIgnoreCase("Indexed") && RESOURCE_GROUP_TYPE.equals(resource.get("type"))) {
            return Optional.empty();
        }
        Map<String, Object> rule = mapOf(properties.get("policyRule"));
        Map<String, Object> then = mapOf(rule.get("then"));
        PolicyEvaluator evaluator = new PolicyEvaluator(withDefaults(properties.get("parameters"), assigned), resource);
        String effect = String.valueOf(evaluator.resolve(then.get("effect"))).toLowerCase(Locale.ROOT);
        boolean blocks = write
                ? effect.equals("deny")
                : effect.equals("denyaction") && strings(mapOf(then.get("details")).get("actionNames")).stream()
                        .anyMatch("delete"::equalsIgnoreCase);
        if (!blocks || !evaluator.matches(rule.get("if"))) {
            return Optional.empty();
        }
        String message = nonComplianceMessage(assignment, referenceId);
        LOG.infov("Policy: {0} refused {1}", assignment.get("id"), resource.get("id"));
        return Optional.of(refusal(assignment, definitionId, resource, write, message));
    }

    /** Azure's {@code RequestDisallowedByPolicy} answer, naming the assignment and definition. */
    private static Response refusal(Map<String, Object> assignment, String definitionId, Map<String, Object> resource,
                                    boolean write, String reason) {
        String name = String.valueOf(resource.get("name"));
        Map<String, Object> identifiers = new LinkedHashMap<>();
        identifiers.put("policyAssignment", Map.of("name", String.valueOf(assignment.get("name")),
                "id", String.valueOf(assignment.get("id"))));
        identifiers.put("policyDefinition", Map.of("name", leaf(definitionId), "id", definitionId));
        String identifierText;
        try {
            identifierText = MAPPER.writeValueAsString(List.of(identifiers));
        } catch (JsonProcessingException e) {
            identifierText = String.valueOf(identifiers);
        }
        String subject = write ? "Resource '" + name + "'" : "Deletion of resource '" + name + "'";
        String message = reason == null
                ? subject + " was disallowed by policy. Policy identifiers: '" + identifierText + "'."
                : subject + " was disallowed by policy. Reasons: '" + reason
                        + "'. See error details for policy resource IDs.";
        Map<String, Object> properties = PolicyHandler.propertiesOf(assignment);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("policyDefinitionId", definitionId);
        info.put("policyDefinitionName", leaf(definitionId));
        info.put("policyDefinitionEffect", write ? "deny" : "denyAction");
        info.put("policyAssignmentId", assignment.get("id"));
        info.put("policyAssignmentName", assignment.get("name"));
        info.put("policyAssignmentDisplayName", properties.get("displayName"));
        info.put("policyAssignmentScope", properties.get("scope"));
        info.put("policyAssignmentParameters", values(properties.get("parameters")));
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "RequestDisallowedByPolicy");
        error.put("target", name);
        error.put("message", message);
        error.put("additionalInfo", List.of(Map.of("type", "PolicyViolation", "info", info)));
        return Response.status(403).entity(Map.of("error", error)).type(MediaType.APPLICATION_JSON).build();
    }

    private static String nonComplianceMessage(Map<String, Object> assignment, String referenceId) {
        String general = null;
        for (Object raw : listOf(PolicyHandler.propertiesOf(assignment).get("nonComplianceMessages"))) {
            if (raw instanceof Map<?, ?> entry && entry.get("message") instanceof String message) {
                Object reference = entry.get("policyDefinitionReferenceId");
                if (reference == null && general == null) {
                    general = message;
                } else if (referenceId != null && referenceId.equalsIgnoreCase(String.valueOf(reference))) {
                    return message;
                }
            }
        }
        return general;
    }

    /** Parameter values, taking the given ones over the definition's defaults. */
    private static Map<String, Object> withDefaults(Object declared, Map<String, Object> given) {
        Map<String, Object> values = new LinkedHashMap<>();
        mapOf(declared).forEach((name, spec) -> {
            if (spec instanceof Map<?, ?> map && map.containsKey("defaultValue")) {
                values.put(name, map.get("defaultValue"));
            }
        });
        values.putAll(given);
        return values;
    }

    /** Unwraps each parameter's {@code value}, keyed by parameter name. */
    private static Map<String, Object> values(Object parameters) {
        Map<String, Object> values = new LinkedHashMap<>();
        mapOf(parameters).forEach((name, spec) -> {
            if (spec instanceof Map<?, ?> map && map.containsKey("value")) {
                values.put(name, map.get("value"));
            }
        });
        return values;
    }

    private static boolean expired(Map<String, Object> exemption) {
        if (!(exemption.get("expiresOn") instanceof String expiresOn)) {
            return false;
        }
        try {
            return Instant.parse(expiresOn).isBefore(Instant.now());
        } catch (DateTimeParseException e) {
            LOG.debugv("Ignoring unparseable exemption expiresOn {0}", expiresOn);
            return false;
        }
    }

    private static String exemptionScope(String exemptionId) {
        int marker = exemptionId.toLowerCase(Locale.ROOT).indexOf(EXEMPTIONS_MARKER);
        return marker <= 0 ? "/" : exemptionId.substring(0, marker);
    }

    /** Whether {@code scope} is {@code target} or lies above it; {@code /} lies above everything. */
    private static boolean covers(String scope, String target) {
        String s = scope.toLowerCase(Locale.ROOT);
        String t = target.toLowerCase(Locale.ROOT);
        return s.equals("/") || t.equals(s) || t.startsWith(s.endsWith("/") ? s : s + "/");
    }

    private static String leaf(String id) {
        return id.substring(id.lastIndexOf('/') + 1);
    }

    private static List<String> strings(Object value) {
        return listOf(value).stream().map(String::valueOf).toList();
    }

    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
