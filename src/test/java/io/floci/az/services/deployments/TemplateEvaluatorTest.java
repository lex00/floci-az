package io.floci.az.services.deployments;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateEvaluatorTest {

    private static final TemplateEvaluator.Scope SCOPE =
            new TemplateEvaluator.Scope("tenant-1", "sub-1", "rg-1", "westus", "deploy-1");

    private static TemplateEvaluator evaluator(Map<String, Object> parameters, Map<String, Object> variables) {
        return new TemplateEvaluator(SCOPE, parameters, variables, new TemplateEvaluator.Resources() {
            @Override
            public Map<String, Object> get(String resourceId, String apiVersion) {
                return Map.of("properties", Map.of("endpoint", "https://" + resourceId.substring(
                        resourceId.lastIndexOf('/') + 1) + ".example"));
            }

            @Override
            public Object post(String resourceId, String action, String apiVersion) {
                return Map.of("keys", List.of(Map.of("value", "k1")));
            }
        });
    }

    @Test
    void scopeFunctionsAndResourceIdsFollowTheDeployment() {
        TemplateEvaluator evaluator = evaluator(Map.of(), Map.of());
        assertThat(evaluator.evaluate("[resourceGroup().location]"), equalTo("westus"));
        assertThat(evaluator.evaluate("[subscription().subscriptionId]"), equalTo("sub-1"));
        assertThat(evaluator.evaluate("[resourceId('Microsoft.Storage/storageAccounts/blobServices', 'acct', 'default')]"),
                equalTo("/subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.Storage/storageAccounts/acct"
                        + "/blobServices/default"));
        assertThat(evaluator.evaluate("[resourceId('other-rg', 'Microsoft.Web/sites', 'app')]"),
                equalTo("/subscriptions/sub-1/resourceGroups/other-rg/providers/Microsoft.Web/sites/app"));
        assertThat(evaluator.evaluate("[subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'r1')]"),
                equalTo("/subscriptions/sub-1/providers/Microsoft.Authorization/roleDefinitions/r1"));
    }

    @Test
    void parametersVariablesAndStringFunctionsCompose() {
        TemplateEvaluator evaluator = evaluator(Map.of("prefix", "Data"),
                Map.of("name", "[toLower(concat(parameters('prefix'), 'store'))]", "tags", Map.of("env", "test")));
        assertThat(evaluator.evaluate("[variables('name')]"), equalTo("datastore"));
        assertThat(evaluator.evaluate("[format('{0}-{1}', variables('name'), 2)]"), equalTo("datastore-2"));
        assertThat(evaluator.evaluate("[variables('tags').env]"), equalTo("test"));
        assertThat(evaluator.evaluate("[if(equals(parameters('prefix'), 'Data'), 'yes', 'no')]"), equalTo("yes"));
        assertThat(evaluator.evaluate("[[literal]"), equalTo("[literal]"));
        assertThat(evaluator.evaluate("[length(split('a,b,c', ','))]"), equalTo(3L));
    }

    @Test
    void referenceAndListKeysReadDeployedResources() {
        TemplateEvaluator evaluator = evaluator(Map.of(), Map.of());
        assertThat(evaluator.evaluate("[reference(resourceId('Microsoft.Web/sites', 'app1')).endpoint]"),
                equalTo("https://app1.example"));
        assertThat(evaluator.evaluate("[listKeys('id', '2023-01-01').keys[0].value]"), equalTo("k1"));
    }

    @Test
    void uniqueStringIsStableAndShapedLikeAzures() {
        TemplateEvaluator evaluator = evaluator(Map.of(), Map.of());
        Object first = evaluator.evaluate("[uniqueString(resourceGroup().id)]");
        assertThat(first, equalTo(evaluator.evaluate("[uniqueString(resourceGroup().id)]")));
        assertThat(String.valueOf(first), matchesPattern("[a-z2-7]{13}"));
    }

    @Test
    void copyLoopsAndPropertyCopyExpand() {
        TemplateEvaluator evaluator = evaluator(Map.of(), Map.of());
        assertThat(evaluator.inCopy("loop", 2).evaluate("[concat('item', copyIndex(1))]"), equalTo("item3"));
        Object expanded = evaluator.evaluate(Map.of("copy", List.of(Map.of("name", "rules", "count", 2,
                "input", Map.of("priority", "[add(100, copyIndex('rules'))]")))));
        assertThat(expanded, equalTo(Map.of("rules", List.of(Map.of("priority", 100L), Map.of("priority", 101L)))));
    }

    @Test
    void unknownFunctionsAndParametersAreTemplateErrors() {
        TemplateEvaluator evaluator = evaluator(Map.of(), Map.of());
        assertThrows(TemplateException.class, () -> evaluator.evaluate("[pickZones('a', 'b', 'c')]"));
        assertThrows(TemplateException.class, () -> evaluator.evaluate("[parameters('missing')]"));
        assertThrows(TemplateException.class, () -> evaluator.evaluate("[concat('a']"));
    }
}
