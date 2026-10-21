package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class TenantScopedGenericResourceTest {

    private static final String MG = "/providers/Microsoft.Management/managementGroups/";
    private static final String API = "?api-version=2021-04-01";

    @Test
    void managementGroupIsCreatedReadAndDeleted() {
        given().contentType("application/json")
            .body("{\"properties\":{\"displayName\":\"Estate\",\"details\":{\"parent\":{\"id\":\"/providers/"
                + "Microsoft.Management/managementGroups/root\"}}}}")
            .when().put(MG + "mg-estate" + API)
            .then().statusCode(201)
            .body("id", equalTo(MG + "mg-estate"))
            .body("name", equalTo("mg-estate"))
            .body("type", equalTo("Microsoft.Management/managementGroups"))
            .body("properties.displayName", equalTo("Estate"));

        given().when().get("/providers/microsoft.management/managementgroups/MG-ESTATE" + API)
            .then().statusCode(200)
            .body("id", equalTo(MG + "mg-estate"))
            .body("properties.details.parent.id", equalTo("/providers/Microsoft.Management/managementGroups/root"));

        given().when().head(MG + "mg-estate" + API).then().statusCode(204);
        given().when().delete(MG + "mg-estate" + API).then().statusCode(200);
        given().when().get(MG + "mg-estate" + API).then().statusCode(404);
    }

    @Test
    void tenantCollectionListsTenantLevelResourcesOfTheType() {
        given().contentType("application/json").body("{\"properties\":{\"displayName\":\"Listed\"}}")
            .when().put(MG + "mg-listed" + API).then().statusCode(201);
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/sub-mg/providers/Microsoft.Management/managementGroups/not-tenant" + API);

        given().when().get("/providers/Microsoft.Management/managementGroups" + API)
            .then().statusCode(200)
            .body("value.name", hasItem("mg-listed"))
            .body("value.name", not(hasItem("not-tenant")));
    }

    @Test
    void resourceAtAManagementGroupScopeNeedsTheGroupAndGoesWithIt() {
        String extension = MG + "mg-scope/providers/Microsoft.Contoso/settings/s1" + API;
        given().contentType("application/json").body("{\"properties\":{\"level\":1}}")
            .when().put(extension).then().statusCode(404);

        given().contentType("application/json").body("{\"properties\":{\"displayName\":\"Scope\"}}")
            .when().put(MG + "mg-scope" + API).then().statusCode(201);
        given().contentType("application/json").body("{\"properties\":{\"level\":1}}")
            .when().put(extension).then().statusCode(201);
        given().when().get(extension).then().statusCode(200).body("properties.level", equalTo(1));

        given().when().delete(MG + "mg-scope" + API).then().statusCode(200);
        given().when().get(extension).then().statusCode(404);
    }

    @Test
    void subscriptionAliasIsKeptAtTenantLevel() {
        given().contentType("application/json")
            .body("{\"properties\":{\"displayName\":\"Sub\",\"workload\":\"Production\"}}")
            .when().put("/providers/Microsoft.Subscription/aliases/alias1" + API)
            .then().statusCode(201)
            .body("type", equalTo("Microsoft.Subscription/aliases"));
    }
}
