package io.floci.az.services.rbac;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(RbacEnforcementTest.EnforceProfile.class)
class RbacEnforcementTest {

    private static final String ADMIN = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String SUB = "/subscriptions/sub-rbac-enforce";
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String READER = "acdd72a7-3385-48ef-bd42-f606fba81ae7";
    private static final String CONTRIBUTOR = "b24988ac-6180-42a0-ab88-20f7382dd24c";

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.rbac.enforce", "true",
                    "floci-az.services.rbac.admin-principals", ADMIN);
        }
    }

    private static String tokenFor(String oid) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(("{\"oid\":\"" + oid + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private static void assign(String scope, String role, String principal) {
        given().header("Authorization", tokenFor(ADMIN)).contentType("application/json")
            .body("{\"properties\":{\"roleDefinitionId\":\"" + SUB + AUTH + "/roleDefinitions/" + role
                    + "\",\"principalId\":\"" + principal + "\"}}")
            .when().put(scope + AUTH + "/roleAssignments/" + UUID.randomUUID() + "?api-version=2022-04-01")
            .then().statusCode(201);
    }

    @Test
    void aReaderCanReadButNotWrite() {
        String rg = SUB + "/resourceGroups/rg-enforce-reader";
        given().header("Authorization", tokenFor(ADMIN)).contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put(rg + "?api-version=2021-04-01")
            .then().statusCode(201);
        String reader = UUID.randomUUID().toString();
        assign(rg, READER, reader);

        given().header("Authorization", tokenFor(reader))
            .when().get(rg + "?api-version=2021-04-01")
            .then().statusCode(200);

        given().header("Authorization", tokenFor(reader)).contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put(rg + "/providers/Microsoft.Storage/storageAccounts/enforceacct1?api-version=2023-01-01")
            .then().statusCode(403)
            .body("error.code", equalTo("AuthorizationFailed"))
            .body("error.message", containsString("'Microsoft.Storage/storageAccounts/write'"))
            .body("error.message", containsString(reader));
    }

    @Test
    void aContributorWritesResourcesButCannotAssignRoles() {
        String rg = SUB + "/resourceGroups/rg-enforce-contrib";
        given().header("Authorization", tokenFor(ADMIN)).contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put(rg + "?api-version=2021-04-01")
            .then().statusCode(201);
        String contributor = UUID.randomUUID().toString();
        assign(rg, CONTRIBUTOR, contributor);

        given().header("Authorization", tokenFor(contributor)).contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put(rg + "/providers/Microsoft.Storage/storageAccounts/enforceacct2?api-version=2023-01-01")
            .then().statusCode(200);

        given().header("Authorization", tokenFor(contributor)).contentType("application/json")
            .body("{\"properties\":{\"roleDefinitionId\":\"" + SUB + AUTH + "/roleDefinitions/" + READER
                    + "\",\"principalId\":\"" + UUID.randomUUID() + "\"}}")
            .when().put(rg + AUTH + "/roleAssignments/" + UUID.randomUUID() + "?api-version=2022-04-01")
            .then().statusCode(403)
            .body("error.message", containsString("'Microsoft.Authorization/roleAssignments/write'"));
    }

    @Test
    void aCallerWithoutAnAssignmentIsRefusedOutsideItsScope() {
        String elsewhere = SUB + "/resourceGroups/rg-enforce-other";
        String principal = UUID.randomUUID().toString();
        assign(SUB + "/resourceGroups/rg-enforce-own", READER, principal);

        given().header("Authorization", tokenFor(principal))
            .when().get(elsewhere + "/providers/Microsoft.Storage/storageAccounts?api-version=2023-01-01")
            .then().statusCode(403)
            .body("error.message", containsString("over scope '" + elsewhere + "'"));
    }

    @Test
    void requestsWithoutATokenAreNotChecked() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(SUB + "/resourceGroups/rg-enforce-anon?api-version=2021-04-01")
            .then().statusCode(201);
    }
}
