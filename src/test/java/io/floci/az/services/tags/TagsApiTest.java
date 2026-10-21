package io.floci.az.services.tags;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class TagsApiTest {

    private static final String SUB = "/subscriptions/sub-tagsapi";
    private static final String TAGS = "/providers/Microsoft.Resources/tags/default?api-version=2021-04-01";

    private static void createGroup(String rg, String body) {
        given().contentType("application/json").body(body)
            .when().put(SUB + "/resourceGroups/" + rg + "?api-version=2021-04-01")
            .then().statusCode(anyOf(is(200), is(201)));
    }

    private static void patchTags(String scope, String operation, String tags) {
        given().contentType("application/json")
            .body("{\"operation\":\"" + operation + "\",\"properties\":{\"tags\":" + tags + "}}")
            .when().patch(scope + TAGS)
            .then().statusCode(200);
    }

    @Test
    void mergeOnAResourceGroupKeepsItsTagsAndAddsTheNewOnes() {
        createGroup("rg-tags-merge", "{\"location\":\"eastus\",\"tags\":{\"team\":\"core\"}}");

        given().contentType("application/json")
            .body("{\"operation\":\"Merge\",\"properties\":{\"tags\":{\"tofu-estate\":\"e1\"}}}")
            .when().patch(SUB + "/resourceGroups/rg-tags-merge" + TAGS)
            .then().statusCode(200)
            .body("id", equalTo(SUB + "/resourceGroups/rg-tags-merge/providers/Microsoft.Resources/tags/default"))
            .body("name", equalTo("default"))
            .body("type", equalTo("Microsoft.Resources/tags"))
            .body("properties.tags", equalTo(Map.of("team", "core", "tofu-estate", "e1")));

        given().when().get(SUB + "/resourceGroups/rg-tags-merge?api-version=2021-04-01")
            .then().statusCode(200)
            .body("tags", equalTo(Map.of("team", "core", "tofu-estate", "e1")));
    }

    @Test
    void mergeThatRespellsANameReplacesIt() {
        createGroup("rg-tags-case", "{\"location\":\"eastus\",\"tags\":{\"tofu-estate\":\"e1\"}}");
        patchTags(SUB + "/resourceGroups/rg-tags-case", "Merge", "{\"Tofu-Estate\":\"e2\"}");

        given().when().get(SUB + "/resourceGroups/rg-tags-case" + TAGS)
            .then().statusCode(200)
            .body("properties.tags", equalTo(Map.of("Tofu-Estate", "e2")));
    }

    @Test
    void replaceDeleteAndDeleteAllOnAGenericResource() {
        createGroup("rg-tags-generic", "{\"location\":\"eastus\"}");
        String id = SUB + "/resourceGroups/rg-tags-generic/providers/Microsoft.Contoso/widgets/w1";
        given().contentType("application/json").body("{\"location\":\"eastus\",\"tags\":{\"a\":\"1\",\"b\":\"2\"}}")
            .when().put(id + "?api-version=2021-04-01")
            .then().statusCode(anyOf(is(200), is(201)));

        patchTags(id, "Replace", "{\"c\":\"3\",\"d\":\"4\",\"e\":\"5\"}");
        patchTags(id, "Delete", "{\"C\":\"\",\"d\":\"other\",\"e\":\"5\"}");
        given().when().get(id + "?api-version=2021-04-01")
            .then().statusCode(200)
            .body("tags", equalTo(Map.of("d", "4")));

        given().when().delete(id + TAGS).then().statusCode(200).body("properties.tags", equalTo(Map.of()));
        given().when().get(id + TAGS).then().statusCode(200).body("properties.tags", equalTo(Map.of()));
    }

    @Test
    void putOnAStorageAccountShowsInItsReadAndInResourceGraph() {
        createGroup("rg-tags-storage", "{\"location\":\"eastus\"}");
        String id = SUB + "/resourceGroups/rg-tags-storage/providers/Microsoft.Storage/storageAccounts/tagsapiacct";
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}")
            .when().put(id + "?api-version=2023-01-01")
            .then().statusCode(anyOf(is(200), is(201)));

        given().contentType("application/json").body("{\"properties\":{\"tags\":{\"tofu-estate\":\"e3\"}}}")
            .when().put(id + TAGS)
            .then().statusCode(200)
            .body("properties.tags", equalTo(Map.of("tofu-estate", "e3")));

        given().when().get(id + "?api-version=2023-01-01")
            .then().statusCode(200)
            .body("tags", equalTo(Map.of("tofu-estate", "e3")));

        given().contentType("application/json")
            .body("{\"subscriptions\":[\"sub-tagsapi\"],\"query\":\"resources | where name == 'tagsapiacct' | project tags\"}")
            .when().post("/providers/Microsoft.ResourceGraph/resources?api-version=2022-10-01")
            .then().statusCode(200)
            .body("data.tags", hasItem(Map.of("tofu-estate", "e3")));
    }

    @Test
    void subscriptionTagsShowInTheSubscriptionAndResourceGraph() {
        given().contentType("application/json").body("{\"properties\":{\"tags\":{\"tofu-estate\":\"e4\"}}}")
            .when().put(SUB + TAGS)
            .then().statusCode(200)
            .body("id", equalTo(SUB + "/providers/Microsoft.Resources/tags/default"))
            .body("properties.tags", equalTo(Map.of("tofu-estate", "e4")));

        given().when().get(SUB + "?api-version=2022-12-01")
            .then().statusCode(200)
            .body("tags", equalTo(Map.of("tofu-estate", "e4")));

        given().contentType("application/json")
            .body("{\"subscriptions\":[\"sub-tagsapi\"],\"query\":\"resourcecontainers "
                + "| where type == 'microsoft.resources/subscriptions' | project tags\"}")
            .when().post("/providers/Microsoft.ResourceGraph/resources?api-version=2022-10-01")
            .then().statusCode(200)
            .body("data.tags", hasItem(Map.of("tofu-estate", "e4")));
    }

    @Test
    void anUnknownOperationIsRefused() {
        createGroup("rg-tags-badop", "{\"location\":\"eastus\"}");
        given().contentType("application/json").body("{\"operation\":\"Append\",\"properties\":{\"tags\":{}}}")
            .when().patch(SUB + "/resourceGroups/rg-tags-badop" + TAGS)
            .then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"));
    }

    @Test
    void aMissingTargetIsNotFound() {
        given().when().get(SUB + "/resourceGroups/rg-tags-missing" + TAGS)
            .then().statusCode(404);
        given().when().get(SUB + "/resourceGroups/rg-tags-missing/providers/Microsoft.Contoso/widgets/none" + TAGS)
            .then().statusCode(404);
    }
}
