package io.floci.az.services.rbac;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class RbacTest {

    private static final String SUB = "/subscriptions/sub-rbac";
    private static final String RG = SUB + "/resourceGroups/rg-rbac";
    private static final String ACCOUNT = RG + "/providers/Microsoft.Storage/storageAccounts/rbacacct1";
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String API = "?api-version=2022-04-01";
    private static final String READER = "acdd72a7-3385-48ef-bd42-f606fba81ae7";
    private static final String BLOB_READER = "2a2b9908-6ea1-4ae2-8e65-a410df84e7d1";

    @BeforeEach
    void createGroup() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(RG + "?api-version=2021-04-01");
    }

    private static String assignmentBody(String role, String principal) {
        return "{\"properties\":{\"roleDefinitionId\":\"" + SUB + AUTH + "/roleDefinitions/" + role
                + "\",\"principalId\":\"" + principal + "\",\"principalType\":\"ServicePrincipal\"}}";
    }

    /** A bearer token whose payload carries {@code oid}; the signature is never read. */
    private static String tokenFor(String oid) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(("{\"oid\":\"" + oid + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void builtInRolesAreListedAndReadable() {
        given().queryParam("$filter", "roleName eq 'Reader'")
            .when().get(SUB + AUTH + "/roleDefinitions" + API)
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo(READER))
            .body("value[0].id", equalTo(SUB + AUTH + "/roleDefinitions/" + READER))
            .body("value[0].properties.type", equalTo("BuiltInRole"))
            .body("value[0].properties.permissions[0].actions", hasItem("*/read"));

        given().when().get(RG + AUTH + "/roleDefinitions/" + READER + API)
            .then().statusCode(200)
            .body("properties.roleName", equalTo("Reader"));
    }

    @Test
    void customRoleIsCreatedListedAndProtectedWhileAssigned() {
        String roleId = UUID.randomUUID().toString();
        given().contentType("application/json")
            .body("{\"properties\":{\"roleName\":\"Estate Reader " + roleId + "\",\"type\":\"CustomRole\","
                    + "\"assignableScopes\":[\"" + SUB + "\"],\"permissions\":[{\"actions\":"
                    + "[\"Microsoft.Storage/storageAccounts/read\"],\"notActions\":[]}]}}")
            .when().put(SUB + AUTH + "/roleDefinitions/" + roleId + API)
            .then().statusCode(201)
            .body("properties.type", equalTo("CustomRole"));

        given().queryParam("$filter", "type eq 'CustomRole'")
            .when().get(RG + AUTH + "/roleDefinitions" + API)
            .then().statusCode(200)
            .body("value.name", hasItem(roleId));

        String assignment = UUID.randomUUID().toString();
        given().contentType("application/json").body(assignmentBody(roleId, UUID.randomUUID().toString()))
            .when().put(RG + AUTH + "/roleAssignments/" + assignment + API)
            .then().statusCode(201);

        given().when().delete(SUB + AUTH + "/roleDefinitions/" + roleId + API)
            .then().statusCode(409)
            .body("error.code", equalTo("RoleDefinitionHasAssignments"));

        given().when().delete(RG + AUTH + "/roleAssignments/" + assignment + API).then().statusCode(200);
        given().when().delete(SUB + AUTH + "/roleDefinitions/" + roleId + API).then().statusCode(200);
        given().when().get(SUB + AUTH + "/roleDefinitions/" + roleId + API).then().statusCode(404);
    }

    @Test
    void roleAssignmentOnAResourceKeepsItsCondition() {
        String assignment = UUID.randomUUID().toString();
        String principal = UUID.randomUUID().toString();
        String condition = "@Resource[Microsoft.Storage/storageAccounts/blobServices/containers:name] "
                + "StringEquals 'estate'";
        given().contentType("application/json")
            .body("{\"properties\":{\"roleDefinitionId\":\"" + SUB + AUTH + "/roleDefinitions/" + BLOB_READER
                    + "\",\"principalId\":\"" + principal + "\",\"condition\":\"" + condition + "\"}}")
            .when().put(ACCOUNT + AUTH + "/roleAssignments/" + assignment + API)
            .then().statusCode(201)
            .body("id", equalTo(ACCOUNT + AUTH + "/roleAssignments/" + assignment))
            .body("type", equalTo("Microsoft.Authorization/roleAssignments"))
            .body("properties.scope", equalTo(ACCOUNT))
            .body("properties.condition", equalTo(condition))
            .body("properties.conditionVersion", equalTo("2.0"))
            .body("properties.principalType", equalTo("User"));

        given().when().get(ACCOUNT + AUTH + "/roleAssignments/" + assignment + API)
            .then().statusCode(200)
            .body("properties.principalId", equalTo(principal));
    }

    @Test
    void assignmentErrorsMatchAzure() {
        String principal = UUID.randomUUID().toString();
        given().contentType("application/json").body(assignmentBody(UUID.randomUUID().toString(), principal))
            .when().put(RG + AUTH + "/roleAssignments/" + UUID.randomUUID() + API)
            .then().statusCode(400)
            .body("error.code", equalTo("RoleDefinitionDoesNotExist"));

        given().contentType("application/json").body(assignmentBody(READER, principal))
            .when().put(RG + AUTH + "/roleAssignments/not-a-guid" + API)
            .then().statusCode(400)
            .body("error.code", equalTo("InvalidRoleAssignmentId"));

        String first = UUID.randomUUID().toString();
        given().contentType("application/json").body(assignmentBody(READER, principal))
            .when().put(RG + AUTH + "/roleAssignments/" + first + API)
            .then().statusCode(201);
        given().contentType("application/json").body(assignmentBody(READER, principal))
            .when().put(RG + AUTH + "/roleAssignments/" + UUID.randomUUID() + API)
            .then().statusCode(409)
            .body("error.code", equalTo("RoleAssignmentExists"));
        given().contentType("application/json").body(assignmentBody(BLOB_READER, principal))
            .when().put(RG + AUTH + "/roleAssignments/" + first + API)
            .then().statusCode(400)
            .body("error.code", equalTo("RoleAssignmentUpdateNotPermitted"));
    }

    @Test
    void listingFollowsScopeAndPrincipalFilters() {
        String principal = UUID.randomUUID().toString();
        String atSubscription = UUID.randomUUID().toString();
        String atAccount = UUID.randomUUID().toString();
        given().contentType("application/json").body(assignmentBody(READER, principal))
            .when().put(SUB + AUTH + "/roleAssignments/" + atSubscription + API)
            .then().statusCode(201);
        given().contentType("application/json").body(assignmentBody(BLOB_READER, principal))
            .when().put(ACCOUNT + AUTH + "/roleAssignments/" + atAccount + API)
            .then().statusCode(201);

        given().queryParam("$filter", "atScope()")
            .when().get(RG + AUTH + "/roleAssignments" + API)
            .then().statusCode(200)
            .body("value.name", hasItem(atSubscription))
            .body("value.name", not(hasItem(atAccount)));

        given().queryParam("$filter", "principalId eq '" + principal + "'")
            .when().get(RG + AUTH + "/roleAssignments" + API)
            .then().statusCode(200)
            .body("value", hasSize(2));
    }

    @Test
    void permissionsReportWhatTheCallerHoldsAtAScope() {
        String principal = UUID.randomUUID().toString();
        given().contentType("application/json").body(assignmentBody(BLOB_READER, principal))
            .when().put(ACCOUNT + AUTH + "/roleAssignments/" + UUID.randomUUID() + API)
            .then().statusCode(201);

        given().header("Authorization", tokenFor(principal))
            .when().get(ACCOUNT + AUTH + "/permissions?api-version=2022-04-01")
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].dataActions",
                    hasItem("Microsoft.Storage/storageAccounts/blobServices/containers/blobs/read"))
            .body("value[0].condition", nullValue());

        given().header("Authorization", tokenFor(principal))
            .when().get(RG + AUTH + "/permissions?api-version=2022-04-01")
            .then().statusCode(200)
            .body("value", hasSize(0));
    }
}
