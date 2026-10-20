package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ArmStorageBlobPropertiesTest {

    private static final String RG = "/subscriptions/sub-blob-props/resourceGroups/rg-blob-props";
    private static final String STORAGE = RG + "/providers/Microsoft.Storage/storageAccounts/";
    private static final String API = "?api-version=2023-01-01";

    @Test
    void allowBlobPublicAccessDefaultsToFalseAndKeepsTheRequestedValue() {
        createAccount("blobprops1", "{\"location\":\"eastus\"}");
        given().when().get(STORAGE + "blobprops1" + API)
            .then().statusCode(200)
            .body("properties.allowBlobPublicAccess", equalTo(false));

        createAccount("blobprops2", "{\"location\":\"eastus\",\"properties\":"
                + "{\"allowBlobPublicAccess\":true,\"minimumTlsVersion\":\"TLS1_2\"}}");
        given().when().get(STORAGE + "blobprops2" + API)
            .then().statusCode(200)
            .body("properties.allowBlobPublicAccess", equalTo(true))
            .body("properties.minimumTlsVersion", equalTo("TLS1_2"));
    }

    @Test
    void patchUpdatesSettablePropertiesAndKeepsReadOnlyOnes() {
        createAccount("blobprops3", "{\"location\":\"eastus\",\"properties\":{\"allowBlobPublicAccess\":true}}");

        given().contentType("application/json")
            .body("{\"properties\":{\"allowBlobPublicAccess\":false,\"provisioningState\":\"Failed\"}}")
            .when().patch(STORAGE + "blobprops3" + API)
            .then().statusCode(200)
            .body("properties.allowBlobPublicAccess", equalTo(false))
            .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(STORAGE + "blobprops3" + API)
            .then().statusCode(200)
            .body("properties.allowBlobPublicAccess", equalTo(false))
            .body("properties.primaryEndpoints.blob", notNullValue());
    }

    @Test
    void blobServicePropertiesAreKept() {
        createAccount("blobprops4", "{\"location\":\"eastus\"}");
        String service = STORAGE + "blobprops4/blobServices/default" + API;

        given().when().get(service)
            .then().statusCode(200)
            .body("name", equalTo("default"))
            .body("properties.deleteRetentionPolicy.enabled", equalTo(false));

        given().contentType("application/json")
            .body("{\"properties\":{\"isVersioningEnabled\":true,"
                    + "\"deleteRetentionPolicy\":{\"enabled\":true,\"days\":7}}}")
            .when().put(service)
            .then().statusCode(200)
            .body("properties.isVersioningEnabled", equalTo(true));

        given().contentType("application/json")
            .body("{\"properties\":{\"changeFeed\":{\"enabled\":true}}}")
            .when().put(service)
            .then().statusCode(200);

        given().when().get(service)
            .then().statusCode(200)
            .body("id", equalTo(RG + "/providers/Microsoft.Storage/storageAccounts/blobprops4/blobServices/default"))
            .body("type", equalTo("Microsoft.Storage/storageAccounts/blobServices"))
            .body("properties.isVersioningEnabled", equalTo(true))
            .body("properties.deleteRetentionPolicy.days", equalTo(7))
            .body("properties.changeFeed.enabled", equalTo(true));
    }

    @Test
    void blobServicePropertiesOfAMissingAccountAreNotFound() {
        given().when().get(STORAGE + "blobpropsnone/blobServices/default" + API)
            .then().statusCode(404);
    }

    @Test
    void managementPolicyIsKeptAndDeleted() {
        createAccount("blobprops5", "{\"location\":\"eastus\"}");
        String policy = STORAGE + "blobprops5/managementPolicies/default" + API;

        given().when().get(policy).then().statusCode(404);

        given().contentType("application/json")
            .body("{\"properties\":{\"policy\":{\"rules\":[{\"name\":\"expire-noncurrent\",\"type\":\"Lifecycle\","
                    + "\"enabled\":true,\"definition\":{\"filters\":{\"blobTypes\":[\"blockBlob\"]},"
                    + "\"actions\":{\"version\":{\"delete\":{\"daysAfterCreationGreaterThan\":30}}}}}]}}}")
            .when().put(policy)
            .then().statusCode(200)
            .body("name", equalTo("DefaultManagementPolicy"))
            .body("properties.lastModifiedTime", notNullValue());

        given().when().get(policy)
            .then().statusCode(200)
            .body("id", equalTo(RG + "/providers/Microsoft.Storage/storageAccounts/blobprops5/managementPolicies/default"))
            .body("type", equalTo("Microsoft.Storage/storageAccounts/managementPolicies"))
            .body("properties.policy.rules[0].name", equalTo("expire-noncurrent"))
            .body("properties.policy.rules[0].definition.actions.version.delete.daysAfterCreationGreaterThan",
                    equalTo(30));

        given().when().delete(policy).then().statusCode(200);
        given().when().get(policy).then().statusCode(404);
    }

    private static void createAccount(String name, String body) {
        given().contentType("application/json").body(body)
            .when().put(STORAGE + name + API)
            .then().statusCode(200);
    }
}
