package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class TagNameCaseTest {

    private static final String RG = "/subscriptions/sub-tagcase/resourceGroups/rg-tagcase";

    @Test
    void resourceGroupKeepsOneTagPerNameWithTheLastSpelling() {
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"tags\":{\"Tofu-Estate\":\"a\",\"tofu-estate\":\"b\"}}")
            .when().put(RG + "?api-version=2021-04-01")
            .then().statusCode(anyOf(is(200), is(201)))
            .body("tags", equalTo(Map.of("tofu-estate", "b")));
    }

    @Test
    void genericResourcePatchThatRespellsANameReplacesIt() {
        String id = RG + "/providers/Microsoft.Contoso/widgets/w1?api-version=2021-04-01";
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(RG + "?api-version=2021-04-01").then().statusCode(anyOf(is(200), is(201)));
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"tags\":{\"tofu-estate\":\"a\"}}")
            .when().put(id).then().statusCode(anyOf(is(200), is(201)));

        given().contentType("application/json")
            .body("{\"tags\":{\"tofu-estate\":\"a\",\"Tofu-Estate\":\"b\"}}")
            .when().patch(id)
            .then().statusCode(200)
            .body("tags", equalTo(Map.of("Tofu-Estate", "b")));

        given().when().get(id)
            .then().statusCode(200)
            .body("tags", equalTo(Map.of("Tofu-Estate", "b")));
    }

    @Test
    void storageAccountKeepsOneTagPerName() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(RG + "?api-version=2021-04-01").then().statusCode(anyOf(is(200), is(201)));
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"},"
                + "\"tags\":{\"Env\":\"dev\",\"ENV\":\"prod\"}}")
            .when().put(RG + "/providers/Microsoft.Storage/storageAccounts/tagcaseacct?api-version=2023-01-01")
            .then().statusCode(anyOf(is(200), is(201)))
            .body("tags", equalTo(Map.of("ENV", "prod")));
    }
}
