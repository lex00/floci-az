package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ArmResourceTagsTest {

    private static final String SUB = "sub-arm-tags";
    private static final String RG = "/subscriptions/" + SUB + "/resourceGroups/rg-arm-tags";
    private static final String STORAGE = RG + "/providers/Microsoft.Storage/storageAccounts/";
    private static final String VAULTS = RG + "/providers/Microsoft.KeyVault/vaults/";

    @Test
    void storageAccountKeepsTagsAcrossGetAndListings() {
        put(STORAGE + "tagsacct1?api-version=2023-01-01",
                "{\"location\":\"eastus\",\"tags\":{\"tofu-estate\":\"e1\",\"owner\":\"team\"}}")
            .body("tags.tofu-estate", equalTo("e1"));

        given().when().get(STORAGE + "tagsacct1?api-version=2023-01-01")
            .then().statusCode(200)
            .body("tags.tofu-estate", equalTo("e1"))
            .body("tags.owner", equalTo("team"));

        given().when().get(RG + "/providers/Microsoft.Storage/storageAccounts?api-version=2023-01-01")
            .then().statusCode(200)
            .body("value.find { it.name == 'tagsacct1' }.tags.tofu-estate", equalTo("e1"));

        given().queryParam("$filter", "tagName eq 'tofu-estate' and tagValue eq 'e1'")
            .when().get("/subscriptions/" + SUB + "/resources?api-version=2021-04-01")
            .then().statusCode(200)
            .body("value.name", hasItem("tagsacct1"));
    }

    @Test
    void storageAccountPatchReplacesTags() {
        put(STORAGE + "tagsacct2?api-version=2023-01-01",
                "{\"location\":\"eastus\",\"tags\":{\"old\":\"1\"}}");

        given().contentType("application/json").body("{\"tags\":{\"new\":\"2\"}}")
            .when().patch(STORAGE + "tagsacct2?api-version=2023-01-01")
            .then().statusCode(200)
            .body("tags.new", equalTo("2"))
            .body("tags.old", nullValue())
            .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(STORAGE + "tagsacct2?api-version=2023-01-01")
            .then().statusCode(200)
            .body("tags.new", equalTo("2"))
            .body("tags.old", nullValue());
    }

    @Test
    void patchOfAMissingStorageAccountIsNotFound() {
        given().contentType("application/json").body("{\"tags\":{\"a\":\"b\"}}")
            .when().patch(STORAGE + "tagsmissing?api-version=2023-01-01")
            .then().statusCode(404);
    }

    @Test
    void keyVaultKeepsTagsAndPatchReplacesThem() {
        put(VAULTS + "tags-kv1?api-version=2023-07-01",
                "{\"location\":\"eastus\",\"tags\":{\"old\":\"1\"},\"properties\":{\"tenantId\":\"t1\"}}")
            .body("tags.old", equalTo("1"));

        given().contentType("application/json").body("{\"tags\":{\"new\":\"2\"}}")
            .when().patch(VAULTS + "tags-kv1?api-version=2023-07-01")
            .then().statusCode(200)
            .body("tags.new", equalTo("2"))
            .body("tags.old", nullValue())
            .body("properties.vaultUri", equalTo("https://tags-kv1.vault.azure.net/"));

        given().when().get(VAULTS + "tags-kv1?api-version=2023-07-01")
            .then().statusCode(200)
            .body("tags.new", equalTo("2"));
    }

    @Test
    void managedHsmKeepsTags() {
        put(RG + "/providers/Microsoft.KeyVault/managedHSMs/tags-hsm1?api-version=2023-07-01",
                "{\"location\":\"eastus\",\"tags\":{\"env\":\"test\"}}");

        given().when().get(RG + "/providers/Microsoft.KeyVault/managedHSMs/tags-hsm1?api-version=2023-07-01")
            .then().statusCode(200)
            .body("tags.env", equalTo("test"));
    }

    @Test
    void webAppKeepsTagsThroughASiteConfigUpdate() {
        String site = RG + "/providers/Microsoft.Web/sites/tags-site1";
        put(site + "?api-version=2023-01-01",
                "{\"location\":\"eastus\",\"tags\":{\"env\":\"test\"},"
                        + "\"properties\":{\"siteConfig\":{\"linuxFxVersion\":\"Python|3.11\"}}}");

        put(site + "/config/web?api-version=2023-01-01",
                "{\"properties\":{\"linuxFxVersion\":\"Python|3.12\"}}");

        given().when().get(site + "?api-version=2023-01-01")
            .then().statusCode(200)
            .body("tags.env", equalTo("test"))
            .body("properties.siteConfig.linuxFxVersion", equalTo("Python|3.12"));
    }

    @Test
    void apiManagementServiceKeepsTags() {
        String service = RG + "/providers/Microsoft.ApiManagement/service/tags-apim1";
        put(service + "?api-version=2022-08-01", "{\"location\":\"eastus\",\"tags\":{\"env\":\"test\"}}");

        given().when().get(service + "?api-version=2022-08-01")
            .then().statusCode(200)
            .body("tags.env", equalTo("test"));
    }

    @Test
    void eventGridTopicKeepsTags() {
        String topic = RG + "/providers/Microsoft.EventGrid/topics/tags-topic1";
        put(topic + "?api-version=2022-06-15", "{\"location\":\"eastus\",\"tags\":{\"env\":\"test\"}}");

        given().when().get(topic + "?api-version=2022-06-15")
            .then().statusCode(200)
            .body("tags.env", equalTo("test"));
    }

    @Test
    void monitorResourcesKeepTags() {
        String[] paths = {
            RG + "/providers/Microsoft.OperationalInsights/workspaces/tags-law1?api-version=2022-10-01",
            RG + "/providers/Microsoft.Insights/dataCollectionEndpoints/tags-dce1?api-version=2022-06-01",
            RG + "/providers/Microsoft.Insights/dataCollectionRules/tags-dcr1?api-version=2022-06-01"
        };
        for (String path : paths) {
            given().contentType("application/json").body("{\"location\":\"eastus\",\"tags\":{\"env\":\"test\"}}")
                .when().put(path)
                .then().statusCode(201)
                .body("tags.env", equalTo("test"));

            given().when().get(path)
                .then().statusCode(200)
                .body("tags.env", equalTo("test"));
        }
    }

    private static ValidatableResponse put(String path, String body) {
        return given().contentType("application/json").body(body)
            .when().put(path)
            .then().statusCode(200);
    }
}
