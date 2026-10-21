package io.floci.az.services.containerapps;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@TestProfile(ContainerAppsHandlerTest.MockedProfile.class)
class EnvironmentSubnetTest {

    private static final String RG = "/subscriptions/sub-envsubnet/resourceGroups/rg-envsubnet";
    private static final String SUBNET = RG + "/providers/Microsoft.Network/virtualNetworks/vnet/subnets/apps";

    private static String environmentBody(String subnetId) {
        return "{\"location\":\"eastus\",\"properties\":{\"vnetConfiguration\":{\"infrastructureSubnetId\":\""
            + subnetId + "\"}}}";
    }

    @Test
    void anEnvironmentOnAMissingSubnetIsRefused() {
        given().contentType("application/json").body(environmentBody(RG
                + "/providers/Microsoft.Network/virtualNetworks/vnet/subnets/absent"))
            .when().put(RG + "/providers/Microsoft.App/managedEnvironments/env-missing?api-version=2025-07-01")
            .then().statusCode(400)
            .body("error.code", equalTo("ManagedEnvironmentSubnetNotFound"));

        given().when().get(RG + "/providers/Microsoft.App/managedEnvironments/env-missing?api-version=2025-07-01")
            .then().statusCode(404);
    }

    @Test
    void anEnvironmentOnAnExistingSubnetIsCreated() {
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"addressSpace\":{\"addressPrefixes\":[\"10.0.0.0/16\"]}}}")
            .when().put(RG + "/providers/Microsoft.Network/virtualNetworks/vnet?api-version=2024-05-01")
            .then().statusCode(anyOf(is(200), is(201)));
        given().contentType("application/json")
            .body("{\"properties\":{\"addressPrefix\":\"10.0.0.0/23\"}}")
            .when().put(SUBNET + "?api-version=2024-05-01")
            .then().statusCode(anyOf(is(200), is(201)));

        given().contentType("application/json").body(environmentBody(SUBNET))
            .when().put(RG + "/providers/Microsoft.App/managedEnvironments/env-ok?api-version=2025-07-01")
            .then().statusCode(anyOf(is(200), is(201)));
    }
}
