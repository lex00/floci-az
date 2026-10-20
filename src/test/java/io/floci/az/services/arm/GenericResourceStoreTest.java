package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class GenericResourceStoreTest {

    private static final String SUB = "sub-generic";
    private static final String RG = "/subscriptions/" + SUB + "/resourceGroups/rg-generic";
    private static final String WIDGETS = RG + "/providers/Contoso.Widgets/widgets/";
    private static final String API = "?api-version=2024-01-01";

    @Test
    void putCreatesThenReplacesAResourceOfAnUnclaimedNamespace() {
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"tags\":{\"env\":\"test\"},\"managedBy\":\"owner\","
                    + "\"kind\":\"basic\",\"properties\":{\"size\":3},\"unknownField\":true}")
            .when().put(WIDGETS + "w1" + API)
            .then().statusCode(201)
            .body("id", equalTo(WIDGETS + "w1"))
            .body("name", equalTo("w1"))
            .body("type", equalTo("Contoso.Widgets/widgets"))
            .body("location", equalTo("eastus"))
            .body("tags.env", equalTo("test"))
            .body("managedBy", equalTo("owner"))
            .body("properties.size", equalTo(3))
            .body("unknownField", nullValue());

        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"size\":4}}")
            .when().put(WIDGETS + "w1" + API)
            .then().statusCode(200)
            .body("properties.size", equalTo(4))
            .body("tags", nullValue());
    }

    @Test
    void idsCompareCaseInsensitively() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(WIDGETS + "MixedCase" + API)
            .then().statusCode(201);

        given().when().get(RG.toLowerCase() + "/providers/contoso.widgets/WIDGETS/mixedcase" + API)
            .then().statusCode(200)
            .body("id", equalTo(WIDGETS + "MixedCase"));
    }

    @Test
    void missingResourceIsNotFound() {
        given().when().get(WIDGETS + "absent" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
        given().when().head(WIDGETS + "absent" + API).then().statusCode(404);
    }

    @Test
    void patchReplacesTagsAndMergesProperties() {
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"tags\":{\"old\":\"1\"},\"properties\":{\"a\":1,\"b\":2}}")
            .when().put(WIDGETS + "w2" + API)
            .then().statusCode(201);

        given().contentType("application/json")
            .body("{\"tags\":{\"new\":\"2\"},\"properties\":{\"b\":3}}")
            .when().patch(WIDGETS + "w2" + API)
            .then().statusCode(200)
            .body("tags.new", equalTo("2"))
            .body("tags.old", nullValue())
            .body("properties.a", equalTo(1))
            .body("properties.b", equalTo(3));
    }

    @Test
    void topLevelResourcesAreListedAndFilteredByTag() {
        given().contentType("application/json")
            .body("{\"location\":\"westus\",\"tags\":{\"estate\":\"e7\"},\"managedBy\":\"m\"}")
            .when().put(WIDGETS + "w3" + API)
            .then().statusCode(201);
        given().contentType("application/json").body("{\"properties\":{}}")
            .when().put(WIDGETS + "w3/gears/g1" + API)
            .then().statusCode(201);

        given().queryParam("$filter", "tagName eq 'estate' and tagValue eq 'e7'")
            .when().get(RG + "/resources?api-version=2021-04-01")
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo("w3"))
            .body("value[0].managedBy", equalTo("m"))
            .body("value[0].properties", nullValue());

        given().when().get("/subscriptions/" + SUB + "/resources?api-version=2021-04-01")
            .then().statusCode(200)
            .body("value.name", hasItem("w3"))
            .body("value.name", not(hasItem("g1")));

        given().when().get(WIDGETS + "w3/gears" + API)
            .then().statusCode(200)
            .body("value.name", equalTo(List.of("g1")));

        given().when().get("/subscriptions/" + SUB + "/providers/Contoso.Widgets/widgets" + API)
            .then().statusCode(200)
            .body("value.name", hasItem("w3"));
    }

    @Test
    void childNeedsItsParentAndGoesWithIt() {
        given().contentType("application/json").body("{}")
            .when().put(WIDGETS + "orphan/gears/g1" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ParentResourceNotFound"));

        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(WIDGETS + "w4" + API)
            .then().statusCode(201);
        given().contentType("application/json").body("{}")
            .when().put(WIDGETS + "w4/gears/g1" + API)
            .then().statusCode(201);

        given().when().delete(WIDGETS + "w4" + API).then().statusCode(200);
        given().when().get(WIDGETS + "w4/gears/g1" + API).then().statusCode(404);
        given().when().delete(WIDGETS + "w4" + API).then().statusCode(204);
    }

    @Test
    void subscriptionLevelResourcesAreKept() {
        String budget = "/subscriptions/" + SUB + "/providers/Contoso.Billing/budgets/b1";
        given().contentType("application/json").body("{\"properties\":{\"amount\":10}}")
            .when().put(budget + API)
            .then().statusCode(201);

        given().when().get(budget + API)
            .then().statusCode(200)
            .body("properties.amount", equalTo(10));
    }

    @Test
    void claimedNamespacesAreNotStoredGenerically() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(RG + "/providers/Microsoft.Storage/unknownThings/x" + API)
            .then().statusCode(not(equalTo(201)));
    }
}
