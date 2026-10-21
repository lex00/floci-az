package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class ResourceTypeRuleTest {

    private static final String RG = "/subscriptions/sub-restype/resourceGroups/rg-restype";

    @Test
    void aTypeWithAnUnderscoreIsRefusedAndNotStored() {
        String id = RG + "/providers/Microsoft.Network/virtualNetworks_bogus/x?api-version=2024-05-01";
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(id)
            .then().statusCode(400)
            .body("error.code", equalTo("InvalidResourceType"));

        given().when().get(id).then().statusCode(404);
    }

    @Test
    void aJoinedChildTypeIsRefused() {
        given().contentType("application/json").body("{\"properties\":{\"addressPrefix\":\"10.0.0.0/24\"}}")
            .when().put(RG + "/providers/Microsoft.Network/virtualNetworks_subnets/vnet/apps?api-version=2024-05-01")
            .then().statusCode(400)
            .body("error.code", equalTo("InvalidResourceType"));
    }

    @Test
    void aNameWithAnUnderscoreIsAccepted() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(RG + "/providers/Microsoft.Network/virtualNetworks/my_vnet?api-version=2024-05-01")
            .then().statusCode(anyOf(is(200), is(201)));
    }
}
