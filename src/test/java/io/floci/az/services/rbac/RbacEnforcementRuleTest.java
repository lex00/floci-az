package io.floci.az.services.rbac;

import io.floci.az.core.arm.ArmResourceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RbacEnforcementRuleTest {

    private static Optional<RbacEnforcementRule.Operation> operation(String path, String method) {
        return RbacEnforcementRule.operation(ArmResourceId.parse(path).orElseThrow(), method);
    }

    @Test
    void operationsAreNamedAsAzureNamesThem() {
        assertThat(operation("subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/a", "PUT"),
                equalTo(Optional.of(new RbacEnforcementRule.Operation("Microsoft.Storage/storageAccounts/write",
                        "/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/a"))));
        assertThat(operation("subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/a/listKeys",
                "POST").orElseThrow().action(), equalTo("Microsoft.Storage/storageAccounts/listKeys/action"));
        assertThat(operation("subscriptions/s/resourceGroups/rg", "DELETE").orElseThrow(),
                equalTo(new RbacEnforcementRule.Operation("Microsoft.Resources/subscriptions/resourceGroups/delete",
                        "/subscriptions/s/resourceGroups/rg")));
        assertThat(operation("subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts", "GET")
                .orElseThrow().scope(), equalTo("/subscriptions/s/resourceGroups/rg"));
        assertTrue(operation("subscriptions/s/locations", "GET").isEmpty());
    }

    @Test
    void wildcardsMatchAnyRunOfCharacters() {
        assertTrue(RbacEnforcementRule.matchesAny(List.of("*"), "Microsoft.Storage/storageAccounts/write"));
        assertTrue(RbacEnforcementRule.matchesAny(List.of("*/read"), "Microsoft.Storage/storageAccounts/read"));
        assertTrue(RbacEnforcementRule.matchesAny(List.of("Microsoft.Authorization/*/Write"),
                "Microsoft.Authorization/roleAssignments/write"));
        assertFalse(RbacEnforcementRule.matchesAny(List.of("*/read"), "Microsoft.Storage/storageAccounts/write"));
    }
}
