package io.floci.az.services.deployments;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class NestedDeploymentsTest {

    private static final String SUB = "/subscriptions/sub-nested";
    private static final String API = "?api-version=2024-03-01";
    private static final String DEPLOYMENTS = "/providers/Microsoft.Resources/deployments/";

    private static void group(String name) {
        given().contentType("application/json").body("{\"location\":\"westus\"}")
            .when().put(SUB + "/resourceGroups/" + name + "?api-version=2021-04-01")
            .then().statusCode(anyOf(is(200), is(201)));
    }

    private static void deploy(String scope, String name, String body) {
        given().contentType("application/json").body(body)
            .when().put(scope + DEPLOYMENTS + name + API)
            .then().statusCode(anyOf(is(200), is(201)))
            .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    void subscriptionDeploymentFencesEachGroupThroughACopyOfInnerNestedDeployments() {
        group("rg-fence-a");
        group("rg-fence-b");
        deploy(SUB, "fence", """
                {"location": "westus", "properties": {"mode": "Incremental",
                 "parameters": {"groups": {"value": ["rg-fence-a", "rg-fence-b"]}, "estate": {"value": "e1"}},
                 "template": {
                  "$schema": "https://schema.management.azure.com/schemas/2018-05-01/subscriptionDeploymentTemplate.json#",
                  "contentVersion": "1.0.0.0",
                  "parameters": {"groups": {"type": "array"}, "estate": {"type": "string"}},
                  "resources": [{
                    "type": "Microsoft.Resources/deployments", "apiVersion": "2022-09-01",
                    "name": "[concat('fence-', parameters('groups')[copyIndex()])]",
                    "resourceGroup": "[parameters('groups')[copyIndex()]]",
                    "copy": {"name": "fences", "count": "[length(parameters('groups'))]"},
                    "properties": {"mode": "Incremental", "expressionEvaluationOptions": {"scope": "inner"},
                      "parameters": {"estate": {"value": "[parameters('estate')]"}},
                      "template": {"contentVersion": "1.0.0.0",
                        "parameters": {"estate": {"type": "string"}},
                        "resources": [{"type": "Contoso.Fence/markers", "apiVersion": "2024-01-01", "name": "marker",
                          "location": "[resourceGroup().location]",
                          "tags": {"tofu-estate": "[parameters('estate')]"}}],
                        "outputs": {"group": {"type": "string", "value": "[resourceGroup().name]"}}}}}]}}}
                """);

        for (String rg : new String[] {"rg-fence-a", "rg-fence-b"}) {
            String scope = SUB + "/resourceGroups/" + rg;
            given().when().get(scope + "/providers/Contoso.Fence/markers/marker?api-version=2024-01-01")
                .then().statusCode(200)
                .body("tags", equalTo(Map.of("tofu-estate", "e1")));
            given().when().get(scope + DEPLOYMENTS + "fence-" + rg + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.outputs.group.value", equalTo(rg));
        }
        given().when().get(SUB + DEPLOYMENTS + "fence/operations" + API)
            .then().statusCode(200)
            .body("value.properties.targetResource.id",
                    hasItem(SUB + "/resourceGroups/rg-fence-a" + DEPLOYMENTS + "fence-rg-fence-a"));
    }

    @Test
    void outerNestedDeploymentSeesTheParentsParametersAndVariables() {
        group("rg-outer-parent");
        group("rg-outer-target");
        deploy(SUB + "/resourceGroups/rg-outer-parent", "outer", """
                {"properties": {"mode": "Incremental",
                 "parameters": {"widget": {"value": "w-outer"}},
                 "template": {"contentVersion": "1.0.0.0",
                  "parameters": {"widget": {"type": "string"}},
                  "variables": {"size": 3},
                  "resources": [{
                    "type": "Microsoft.Resources/deployments", "apiVersion": "2022-09-01", "name": "into-target",
                    "resourceGroup": "rg-outer-target",
                    "properties": {"mode": "Incremental",
                      "template": {"contentVersion": "1.0.0.0",
                        "resources": [{"type": "Contoso.Widgets/widgets", "apiVersion": "2024-01-01",
                          "name": "[parameters('widget')]", "location": "westus",
                          "properties": {"size": "[variables('size')]"}}]}}}]}}}
                """);

        given().when().get(SUB + "/resourceGroups/rg-outer-target/providers/Contoso.Widgets/widgets/w-outer"
                + "?api-version=2024-01-01")
            .then().statusCode(200)
            .body("properties.size", equalTo(3));
    }

    @Test
    void aFailedNestedDeploymentFailsTheParent() {
        group("rg-nested-fail");
        given().contentType("application/json").body("""
                {"properties": {"mode": "Incremental",
                 "template": {"contentVersion": "1.0.0.0",
                  "resources": [{
                    "type": "Microsoft.Resources/deployments", "apiVersion": "2022-09-01", "name": "into-missing",
                    "resourceGroup": "rg-nested-missing",
                    "properties": {"mode": "Incremental", "template": {"contentVersion": "1.0.0.0", "resources": []}}}]}}}
                """)
            .when().put(SUB + "/resourceGroups/rg-nested-fail" + DEPLOYMENTS + "fails" + API)
            .then().statusCode(anyOf(is(200), is(201)))
            .body("properties.provisioningState", equalTo("Failed"))
            .body("properties.error.code", equalTo("DeploymentFailed"));
    }
}
