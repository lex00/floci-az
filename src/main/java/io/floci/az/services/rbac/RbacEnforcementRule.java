package io.floci.az.services.rbac;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmRequestRule;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmVerdict;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The gate rule that holds a caller to its role assignments under {@code floci-az.services.rbac.enforce}. The
 * request's operation is named as Azure names it ({@code Microsoft.Storage/storageAccounts/write},
 * {@code Microsoft.Resources/subscriptions/resourceGroups/read}), and the caller needs a role at the target's
 * scope or above whose {@code actions} match it and whose {@code notActions} do not. Otherwise the request is
 * refused with {@code 403 AuthorizationFailed} before its handler runs.
 *
 * <p>Only callers whose bearer token carries an {@code oid} are checked, and the configured admin principals are
 * exempt, so the estate can be set up before anyone is held to it. Listings of tenants and subscriptions stay
 * open, as Azure filters them rather than refusing them. Data actions and ABAC conditions apply to the data
 * plane and play no part here.
 */
@ApplicationScoped
public class RbacEnforcementRule implements ArmRequestRule {

    private static final Logger LOG = Logger.getLogger(RbacEnforcementRule.class);

    /** Position in the gate: after the subscription rule and before the resource-group rule, as ARM checks access first. */
    public static final int ORDER = 2;

    private final RbacStore store;
    private final boolean enforce;
    private final Set<String> admins;

    @Inject
    public RbacEnforcementRule(RbacStore store, EmulatorConfig config) {
        this.store = store;
        this.enforce = config.services().rbac().enabled() && config.services().rbac().enforce();
        this.admins = config.services().rbac().adminPrincipals().orElse(List.of()).stream()
                .map(id -> id.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (!enforce || call.callerObjectId() == null || call.id() == null
                || admins.contains(call.callerObjectId().toLowerCase(Locale.ROOT))) {
            return ArmVerdict.proceed();
        }
        Optional<Operation> operation = operation(call.id(), call.method());
        if (operation.isEmpty() || allowed(call.callerObjectId(), operation.get())) {
            return ArmVerdict.proceed();
        }
        String caller = call.callerObjectId();
        LOG.infov("RBAC: refused {0} on {1} to {2}", operation.get().action(), operation.get().scope(), caller);
        return ArmVerdict.respond(ArmErrors.error(403, "AuthorizationFailed", "The client '" + caller
                + "' with object id '" + caller + "' does not have authorization to perform action '"
                + operation.get().action() + "' over scope '" + operation.get().scope()
                + "' or the scope is invalid. If access was recently granted, please refresh your credentials."));
    }

    /** An operation as Azure names it, and the scope its permission is checked at. */
    record Operation(String action, String scope) {
    }

    /**
     * The operation {@code method} on {@code id} performs, or empty for a request every caller may make: the
     * tenant and subscription listings and anything outside a subscription.
     */
    static Optional<Operation> operation(ArmResourceId id, String method) {
        if (id.subscriptionId() == null) {
            return Optional.empty();
        }
        String subscription = "/subscriptions/" + id.subscriptionId();
        if (id.providerNamespace() == null) {
            return builtInOperation(id, method, subscription);
        }
        if (id.typePath() == null) {
            return Optional.empty();
        }
        boolean collection = id.isCollection();
        String verb;
        String type;
        String scope;
        if (collection && "POST".equals(method)) {
            verb = "action";
            type = id.typePath();
            scope = id.parent() == null ? subscription : id.parent().id();
        } else {
            verb = verb(method);
            type = id.typePath();
            scope = collection ? (id.parent() == null ? subscription : id.parent().id()) : id.id();
        }
        return verb == null ? Optional.empty() : Optional.of(new Operation(type + "/" + verb, scope));
    }

    /** {@code Microsoft.Resources} operations on a subscription, its resource groups and its resource listings. */
    private static Optional<Operation> builtInOperation(ArmResourceId id, String method, String subscription) {
        String verb = verb(method);
        if (verb == null) {
            return Optional.empty();
        }
        String last = id.types().isEmpty() ? "" : id.types().getLast().toLowerCase(Locale.ROOT);
        if (id.resourceGroup() != null) {
            String group = subscription + "/resourceGroups/" + id.resourceGroup();
            String type = last.equals("resources")
                    ? "Microsoft.Resources/subscriptions/resourceGroups/resources"
                    : "Microsoft.Resources/subscriptions/resourceGroups";
            return Optional.of(new Operation(type + "/" + verb, group));
        }
        return switch (last) {
            case "resourcegroups" -> Optional.of(new Operation(
                    "Microsoft.Resources/subscriptions/resourceGroups/" + verb, subscription));
            case "resources" -> Optional.of(new Operation(
                    "Microsoft.Resources/subscriptions/resources/" + verb, subscription));
            case "" -> Optional.of(new Operation("Microsoft.Resources/subscriptions/" + verb, subscription));
            default -> Optional.empty();
        };
    }

    private static String verb(String method) {
        return switch (method == null ? "" : method) {
            case "GET", "HEAD" -> "read";
            case "PUT", "PATCH" -> "write";
            case "DELETE" -> "delete";
            default -> null;
        };
    }

    private boolean allowed(String principal, Operation operation) {
        for (Map<String, Object> permission : store.permissions(principal, operation.scope())) {
            if (matchesAny(permission.get("actions"), operation.action())
                    && !matchesAny(permission.get("notActions"), operation.action())) {
                return true;
            }
        }
        return false;
    }

    /** Whether any pattern in {@code patterns} matches {@code action}; {@code *} stands for any run of characters. */
    static boolean matchesAny(Object patterns, String action) {
        if (!(patterns instanceof List<?> list)) {
            return false;
        }
        for (Object pattern : list) {
            String regex = Pattern.quote(String.valueOf(pattern)).replace("*", "\\E.*\\Q");
            if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(action).matches()) {
                return true;
            }
        }
        return false;
    }
}
