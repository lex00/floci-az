package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.anyOf;

@QuarkusTest
class WorkspaceListsTest {

    private static final String SUB = "/subscriptions/sub-deleted-ws";
    private static final String LIST =
        SUB + "/providers/Microsoft.OperationalInsights/deletedWorkspaces?api-version=2023-09-01";

    @Test
    void listIsEmptyWhenNothingWasDeleted() {
        given().when().get(LIST)
            .then().statusCode(200)
            .body("value", empty());
    }

    @Test
    void listStaysEmptyAfterAWorkspaceIsDeleted() {
        String ws = SUB + "/resourceGroups/rg-deleted-ws/providers/Microsoft.OperationalInsights/workspaces/gone";
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(ws + "?api-version=2023-09-01")
            .then().statusCode(anyOf(is(200), is(201)));
        given().when().delete(ws + "?api-version=2023-09-01")
            .then().statusCode(anyOf(is(200), is(202), is(204)));

        given().when().get(LIST)
            .then().statusCode(200)
            .body("value", hasSize(0));
    }

    @Test
    void workspaceListsServeTheSubscriptionAndTheGroupAndCarryTheCustomerId() {
        String sub = "/subscriptions/sub-list-ws";
        String ws = sub + "/resourceGroups/rg-list-ws/providers/Microsoft.OperationalInsights/workspaces/";
        String customerId = given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(ws + "mine?api-version=2023-09-01")
            .then().statusCode(anyOf(is(200), is(201)))
            .extract().path("properties.customerId");

        given().when().get(sub + "/providers/Microsoft.OperationalInsights/workspaces?api-version=2023-09-01")
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].id", equalTo(ws + "mine"))
            .body("value[0].properties.customerId", equalTo(customerId));

        given().when().get(sub + "/resourceGroups/rg-list-ws/providers/Microsoft.OperationalInsights/workspaces?api-version=2023-09-01")
            .then().statusCode(200)
            .body("value", hasSize(1));

        given().when().get(sub + "/resourceGroups/rg-other/providers/Microsoft.OperationalInsights/workspaces?api-version=2023-09-01")
            .then().statusCode(200)
            .body("value", empty());

        given().when().get("/subscriptions/sub-list-other/providers/Microsoft.OperationalInsights/workspaces?api-version=2023-09-01")
            .then().statusCode(200)
            .body("value", empty());
    }
}
