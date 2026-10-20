package io.floci.az.services.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEvaluatorTest {

    private static final Map<String, Object> ACCOUNT = Map.of(
            "type", "Microsoft.Storage/storageAccounts", "name", "acct1", "location", "eastus",
            "tags", Map.of("Estate", "e1", "owner", "team"));

    private static boolean matches(Map<String, Object> parameters, Object condition) {
        return new PolicyEvaluator(parameters, ACCOUNT).matches(condition);
    }

    @Test
    void typeAndTagConditionsCompareWithoutCase() {
        assertTrue(matches(Map.of(), Map.of("allOf", List.of(
                Map.of("field", "type", "equals", "microsoft.storage/storageaccounts"),
                Map.of("field", "tags['estate']", "equals", "E1")))));
        assertTrue(matches(Map.of(), Map.of("field", "tags.owner", "exists", "true")));
        assertFalse(matches(Map.of(), Map.of("field", "tags[missing]", "exists", true)));
        assertTrue(matches(Map.of(), Map.of("field", "tags", "containsKey", "ESTATE")));
    }

    @Test
    void parametersAndTemplateFunctionsResolve() {
        Map<String, Object> parameters = Map.of("tagName", "estate", "allowed", List.of("westus", "eastus"));
        assertTrue(matches(parameters, Map.of("field", "[concat('tags[', parameters('tagName'), ']')]",
                "equals", "e1")));
        assertTrue(matches(parameters, Map.of("field", "location", "in", "[parameters('allowed')]")));
        assertTrue(matches(parameters, Map.of("value", "[toLower(field('name'))]", "like", "acct*")));
    }

    @Test
    void missingFieldsCompareAsEmpty() {
        assertTrue(matches(Map.of(), Map.of("field", "tags['env']", "notEquals", "prod")));
        assertTrue(matches(Map.of(), Map.of("not", Map.of("field", "tags['env']", "in", List.of("prod", "dev")))));
    }

    @Test
    void matchUsesAzurePatternCharacters() {
        assertTrue(matches(Map.of(), Map.of("field", "name", "match", "????#")));
        assertFalse(matches(Map.of(), Map.of("field", "name", "match", "ACCT#")));
        assertTrue(matches(Map.of(), Map.of("field", "name", "matchInsensitively", "ACCT#")));
    }

    @Test
    void unsupportedShapesAreNotEvaluable() {
        assertThrows(PolicyEvaluator.NotEvaluable.class, () -> matches(Map.of(),
                Map.of("field", "Microsoft.Storage/storageAccounts/allowBlobPublicAccess", "equals", true)));
        assertThrows(PolicyEvaluator.NotEvaluable.class, () -> matches(Map.of(),
                Map.of("count", Map.of("field", "x[*]"), "greater", 0)));
        assertThrows(PolicyEvaluator.NotEvaluable.class, () -> matches(Map.of(),
                Map.of("value", "[resourceGroup().name]", "equals", "rg")));
    }
}
