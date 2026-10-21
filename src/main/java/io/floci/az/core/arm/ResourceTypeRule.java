package io.floci.az.core.arm;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The gate rule for the resource type a {@code PUT} names: a type segment with an underscore names no ARM type,
 * so the write is refused with ARM's {@code 400 InvalidResourceType} before a handler stores it. Such a type
 * comes from a template that joins a parent and child type ({@code virtualNetworks_subnets}) where ARM nests
 * them ({@code virtualNetworks/subnets}).
 */
@ApplicationScoped
public class ResourceTypeRule implements ArmRequestRule {

    /** Position in the gate: after the resource group rule, so the checks on the caller and scope answer first. */
    public static final int ORDER = 5;

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (!"PUT".equals(call.method())) {
            return ArmVerdict.proceed();
        }
        for (ArmResourceId section = call.id(); section != null; section = section.scope()) {
            if (section.providerNamespace() == null) {
                continue;
            }
            for (String type : section.types()) {
                if (type.contains("_")) {
                    return ArmVerdict.respond(ArmErrors.error(400, "InvalidResourceType",
                        "The resource type '" + type + "' could not be found in the namespace '"
                            + section.providerNamespace() + "'."));
                }
            }
        }
        return ArmVerdict.proceed();
    }
}
