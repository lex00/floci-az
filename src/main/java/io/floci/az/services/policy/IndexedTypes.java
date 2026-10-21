package io.floci.az.services.policy;

import io.floci.az.core.arm.ArmResourceId;

import java.util.Locale;
import java.util.Set;

/**
 * Which resource types an {@code Indexed} policy evaluates: Azure's Indexed mode covers only types that support
 * tags and location. Most top-level types do. Nested children and extension resources mostly do not, so they are
 * left out unless listed in {@link #TAGGABLE_CHILDREN}; the top-level types that take no tags are listed in
 * {@link #UNTAGGABLE}.
 */
final class IndexedTypes {

    /** Nested child types that carry tags and a location of their own. */
    private static final Set<String> TAGGABLE_CHILDREN = Set.of(
            "microsoft.sql/servers/databases",
            "microsoft.sql/servers/elasticpools",
            "microsoft.sql/managedinstances/databases",
            "microsoft.web/sites/slots",
            "microsoft.compute/virtualmachines/extensions",
            "microsoft.network/privatednszones/virtualnetworklinks",
            "microsoft.synapse/workspaces/sqlpools",
            "microsoft.synapse/workspaces/bigdatapools",
            "microsoft.cdn/profiles/endpoints",
            "microsoft.machinelearningservices/workspaces/computes",
            "microsoft.devices/iothubs/eventhubendpoints",
            "microsoft.logic/integrationaccounts/maps",
            "microsoft.datafactory/factories/integrationruntimes");

    /** Top-level types that take no tags or no location. */
    private static final Set<String> UNTAGGABLE = Set.of(
            "microsoft.authorization/locks",
            "microsoft.authorization/roleassignments",
            "microsoft.authorization/roledefinitions",
            "microsoft.resources/deployments",
            "microsoft.resources/tags",
            "microsoft.insights/diagnosticsettings",
            "microsoft.security/pricings",
            "microsoft.advisor/configurations",
            "microsoft.consumption/budgets");

    private IndexedTypes() {
    }

    /** Whether an {@code Indexed} policy evaluates a resource at {@code id} of type {@code type}. */
    static boolean evaluated(ArmResourceId id, String type) {
        String key = type.toLowerCase(Locale.ROOT);
        if (UNTAGGABLE.contains(key)) {
            return false;
        }
        if (id.isExtension() || id.types().size() > 1) {
            return TAGGABLE_CHILDREN.contains(key);
        }
        return true;
    }
}
