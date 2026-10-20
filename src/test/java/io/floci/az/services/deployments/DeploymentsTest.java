package io.floci.az.services.deployments;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class DeploymentsTest {

    private static final String SUB = "/subscriptions/sub-deploy";
    private static final String API = "?api-version=2024-03-01";

    private static String group(String name) {
        String rg = SUB + "/resourceGroups/" + name;
        given().contentType("application/json").body("{\"location\":\"westus\"}")
            .when().put(rg + "?api-version=2021-04-01").then().statusCode(201);
        return rg;
    }

    private static String deployment(String scope, String name) {
        return scope + "/providers/Microsoft.Resources/deployments/" + name;
    }

    private static final String TEMPLATE = """
            {"$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#",
             "contentVersion": "1.0.0.0",
             "parameters": {
               "accountName": {"type": "string"},
               "location": {"type": "string", "defaultValue": "[resourceGroup().location]"},
               "identityCount": {"type": "int", "defaultValue": 2}
             },
             "variables": {"tags": {"estate": "[parameters('accountName')]"}},
             "resources": [
               {"type": "Microsoft.Storage/storageAccounts/blobServices/containers", "apiVersion": "2023-01-01",
                "name": "[concat(parameters('accountName'), '/default/data')]",
                "dependsOn": ["[resourceId('Microsoft.Storage/storageAccounts', parameters('accountName'))]"]},
               {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
                "name": "[parameters('accountName')]", "location": "[parameters('location')]",
                "tags": "[variables('tags')]", "kind": "StorageV2", "sku": {"name": "Standard_LRS"},
                "properties": {"allowBlobPublicAccess": false}},
               {"type": "Microsoft.ManagedIdentity/userAssignedIdentities", "apiVersion": "2023-01-31",
                "name": "[concat('id-', copyIndex())]", "location": "[parameters('location')]",
                "copy": {"name": "identities", "count": "[parameters('identityCount')]"}},
               {"type": "Contoso.Widgets/widgets", "apiVersion": "2024-01-01", "name": "w1",
                "location": "[parameters('location')]", "dependsOn": ["identities"],
                "properties": {"size": "[parameters('identityCount')]"}}
             ],
             "outputs": {
               "blobEndpoint": {"type": "string",
                 "value": "[reference(resourceId('Microsoft.Storage/storageAccounts', parameters('accountName'))).primaryEndpoints.blob]"},
               "accountId": {"type": "string",
                 "value": "[resourceId('Microsoft.Storage/storageAccounts', parameters('accountName'))]"},
               "identityNames": {"type": "array", "copy": {"count": 2, "input": "[concat('id-', copyIndex())]"}}
             }}""";

    private static String deployBody(String template, String parameters) {
        return "{\"properties\":{\"mode\":\"Incremental\",\"template\":" + template + ",\"parameters\":"
                + parameters + "}}";
    }

    @Test
    void templateResourcesAreProvisionedInDependencyOrder() {
        String rg = group("rg-deploy-ok");
        String deployment = deployment(rg, "main");
        given().contentType("application/json")
            .body(deployBody(TEMPLATE, "{\"accountName\":{\"value\":\"deployacct1\"}}"))
            .when().put(deployment + API)
            .then().statusCode(201)
            .body("type", equalTo("Microsoft.Resources/deployments"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.parameters.location.value", equalTo("westus"))
            .body("properties.outputs.accountId.value",
                    equalTo(rg + "/providers/Microsoft.Storage/storageAccounts/deployacct1"))
            .body("properties.outputs.blobEndpoint.value", containsString("deployacct1.blob."))
            .body("properties.outputs.identityNames.value", equalTo(List.of("id-0", "id-1")))
            .body("properties.outputResources", hasSize(5));

        given().when().get(rg + "/providers/Microsoft.Storage/storageAccounts/deployacct1?api-version=2023-01-01")
            .then().statusCode(200)
            .body("tags.estate", equalTo("deployacct1"));
        given().when().get(rg + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/id-1?api-version=2023-01-31")
            .then().statusCode(200);
        given().when().get(rg + "/providers/Contoso.Widgets/widgets/w1?api-version=2024-01-01")
            .then().statusCode(200)
            .body("properties.size", equalTo(2));

        given().when().get(deployment + "/operations" + API)
            .then().statusCode(200)
            .body("value", hasSize(5))
            .body("value[0].properties.targetResource.resourceType", equalTo("Microsoft.Storage/storageAccounts"))
            .body("value[1].properties.targetResource.resourceType",
                    equalTo("Microsoft.Storage/storageAccounts/blobServices/containers"));

        given().when().get(rg + "/providers/Microsoft.Resources/deployments" + API)
            .then().statusCode(200)
            .body("value.name", hasItem("main"));
        given().when().post(deployment + "/exportTemplate" + API)
            .then().statusCode(200)
            .body("template.contentVersion", equalTo("1.0.0.0"));
        given().when().head(deployment + API).then().statusCode(204);
    }

    @Test
    void aFailingResourceFailsTheDeployment() {
        String rg = group("rg-deploy-fail");
        given().contentType("application/json")
            .body(deployBody("{\"resources\":[{\"type\":\"Microsoft.Storage/storageAccounts\",\"apiVersion\":"
                    + "\"2023-01-01\",\"name\":\"Not_Valid\",\"location\":\"westus\"}]}", "{}"))
            .when().put(deployment(rg, "bad") + API)
            .then().statusCode(201)
            .body("properties.provisioningState", equalTo("Failed"))
            .body("properties.error.code", equalTo("DeploymentFailed"))
            .body("properties.error.details[0].code", equalTo("AccountNameInvalid"));

        given().when().get(deployment(rg, "bad") + "/operations" + API)
            .then().statusCode(200)
            .body("value[0].properties.provisioningState", equalTo("Failed"))
            .body("value[0].properties.statusCode", equalTo("BadRequest"));
    }

    @Test
    void invalidTemplatesAreRefusedBeforeAnythingIsDeployed() {
        String rg = group("rg-deploy-invalid");
        given().contentType("application/json")
            .body(deployBody(TEMPLATE, "{}"))
            .when().put(deployment(rg, "missing-param") + API)
            .then().statusCode(400)
            .body("error.code", equalTo("InvalidTemplate"))
            .body("error.message", containsString("accountName"));

        given().contentType("application/json")
            .body(deployBody("{\"resources\":[{\"type\":\"Contoso.Widgets/widgets\",\"apiVersion\":\"1\",\"name\":\"a\","
                    + "\"dependsOn\":[\"b\"]},{\"type\":\"Contoso.Widgets/widgets\",\"apiVersion\":\"1\",\"name\":\"b\","
                    + "\"dependsOn\":[\"a\"]}]}", "{}"))
            .when().put(deployment(rg, "cycle") + API)
            .then().statusCode(400)
            .body("error.message", containsString("Circular dependency"));

        given().when().get(deployment(rg, "cycle") + API)
            .then().statusCode(404)
            .body("error.code", equalTo("DeploymentNotFound"));
    }

    @Test
    void validateReportsTheResourcesWithoutDeployingThem() {
        String rg = group("rg-deploy-validate");
        given().contentType("application/json")
            .body(deployBody(TEMPLATE, "{\"accountName\":{\"value\":\"deployacct2\"}}"))
            .when().post(deployment(rg, "check") + "/validate" + API)
            .then().statusCode(200)
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.validatedResources", hasSize(5));

        given().when().get(rg + "/providers/Microsoft.Storage/storageAccounts/deployacct2?api-version=2023-01-01")
            .then().statusCode(404);
    }

    @Test
    void subscriptionDeploymentCreatesAResourceGroup() {
        String body = "{\"location\":\"eastus\",\"properties\":{\"mode\":\"Incremental\",\"template\":"
                + "{\"resources\":[{\"type\":\"Microsoft.Resources/resourceGroups\",\"apiVersion\":\"2021-04-01\","
                + "\"name\":\"rg-from-template\",\"location\":\"eastus\",\"tags\":{\"made\":\"template\"}}],"
                + "\"outputs\":{\"group\":{\"type\":\"string\",\"value\":\"[subscriptionResourceId("
                + "'Microsoft.Resources/resourceGroups', 'rg-from-template')]\"}}}}}";
        given().contentType("application/json").body(body)
            .when().put(deployment(SUB, "groups") + API)
            .then().statusCode(201)
            .body("location", equalTo("eastus"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.outputs.group.value", notNullValue());

        given().when().get(SUB + "/resourceGroups/rg-from-template?api-version=2021-04-01")
            .then().statusCode(200)
            .body("tags.made", equalTo("template"));

        given().contentType("application/json").body(body.replace("\"location\":\"eastus\",\"properties\"",
                "\"properties\""))
            .when().put(deployment(SUB, "no-location") + API)
            .then().statusCode(400)
            .body("error.code", equalTo("LocationRequired"));
    }

    @Test
    void completeModeRemovesResourcesTheTemplateLeavesOut() {
        String rg = group("rg-deploy-complete");
        given().contentType("application/json").body("{\"location\":\"westus\"}")
            .when().put(rg + "/providers/Contoso.Widgets/widgets/stray?api-version=2024-01-01")
            .then().statusCode(201);

        given().contentType("application/json")
            .body("{\"properties\":{\"mode\":\"Complete\",\"template\":{\"resources\":[{\"type\":"
                    + "\"Contoso.Widgets/widgets\",\"apiVersion\":\"2024-01-01\",\"name\":\"kept\","
                    + "\"location\":\"westus\"}]}}}")
            .when().put(deployment(rg, "complete") + API)
            .then().statusCode(201)
            .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(rg + "/providers/Contoso.Widgets/widgets/stray?api-version=2024-01-01")
            .then().statusCode(404);
        given().when().get(rg + "/providers/Contoso.Widgets/widgets/kept?api-version=2024-01-01")
            .then().statusCode(200);
    }
}
