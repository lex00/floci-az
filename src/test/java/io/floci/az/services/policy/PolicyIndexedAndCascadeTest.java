package io.floci.az.services.policy;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@TestProfile(PolicyEvaluationTest.EvaluateProfile.class)
class PolicyIndexedAndCascadeTest {

    private static final String SUB = "/subscriptions/sub-policy-indexed";
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String DEFINITIONS = SUB + AUTH + "/policyDefinitions";
    private static final String API = "?api-version=2023-04-01";

    private static void put(String path, String body) {
        given().contentType("application/json").body(body).when().put(path + API)
            .then().statusCode(anyOf(is(200), is(201)));
    }

    private static String group(String name) {
        String rg = SUB + "/resourceGroups/" + name;
        put(rg, "{\"location\":\"eastus\"}");
        return rg;
    }

    private static void assign(String scope, String name, String definition) {
        put(scope + AUTH + "/policyAssignments/" + name,
                "{\"properties\":{\"policyDefinitionId\":\"" + DEFINITIONS + "/" + definition + "\"}}");
    }

    private static void keepLockedDefinition(String name, String cascade) {
        put(DEFINITIONS + "/" + name, "{\"properties\":{\"mode\":\"Indexed\",\"policyRule\":{\"if\":"
                + "{\"field\":\"tags['estate']\",\"equals\":\"locked\"},\"then\":{\"effect\":\"denyAction\","
                + "\"details\":{\"actionNames\":[\"delete\"]" + cascade + "}}}}}");
    }

    @Test
    void indexedDenyOnAMissingTagSkipsSubnets() {
        put(DEFINITIONS + "/indexed-require-tag", "{\"properties\":{\"mode\":\"Indexed\",\"policyRule\":{\"if\":"
                + "{\"field\":\"tags['estate']\",\"exists\":\"false\"},\"then\":{\"effect\":\"deny\"}}}}");
        String rg = group("rg-indexed-subnet");
        assign(rg, "indexed-require-tag", "indexed-require-tag");
        String vnet = rg + "/providers/Microsoft.Network/virtualNetworks/vnet-indexed";

        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"addressSpace\":{\"addressPrefixes\":[\"10.0.0.0/16\"]}}}")
            .when().put(vnet + "?api-version=2023-09-01")
            .then().statusCode(403);

        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"tags\":{\"estate\":\"e1\"},"
                + "\"properties\":{\"addressSpace\":{\"addressPrefixes\":[\"10.0.0.0/16\"]}}}")
            .when().put(vnet + "?api-version=2023-09-01")
            .then().statusCode(anyOf(is(200), is(201)));
        given().contentType("application/json").body("{\"properties\":{\"addressPrefix\":\"10.0.1.0/24\"}}")
            .when().put(vnet + "/subnets/untagged?api-version=2023-09-01")
            .then().statusCode(anyOf(is(200), is(201)));
    }

    @Test
    void denyActionRefusesDeletingTheGroupOfAProtectedResource() {
        keepLockedDefinition("keep-locked-cascade", "");
        String rg = group("rg-cascade-deny");
        assign(rg, "keep-locked-cascade", "keep-locked-cascade");
        given().contentType("application/json").body("{\"location\":\"eastus\",\"tags\":{\"estate\":\"locked\"}}")
            .when().put(rg + "/providers/Microsoft.Storage/storageAccounts/cascadeacct1?api-version=2023-01-01")
            .then().statusCode(anyOf(is(200), is(201)));

        given().when().delete(rg + "?api-version=2021-04-01")
            .then().statusCode(403)
            .body("error.code", equalTo("RequestDisallowedByPolicy"))
            .body("error.message", containsString("cascadeacct1"));
        given().when().get(rg + "?api-version=2021-04-01").then().statusCode(200);
    }

    @Test
    void cascadeAllowLetsTheGroupGo() {
        keepLockedDefinition("keep-locked-allow", ",\"cascadeBehaviors\":{\"resourceGroup\":\"allow\"}");
        String rg = group("rg-cascade-allow");
        assign(rg, "keep-locked-allow", "keep-locked-allow");
        given().contentType("application/json").body("{\"location\":\"eastus\",\"tags\":{\"estate\":\"locked\"}}")
            .when().put(rg + "/providers/Microsoft.Storage/storageAccounts/cascadeacct2?api-version=2023-01-01")
            .then().statusCode(anyOf(is(200), is(201)));

        given().when().delete(rg + "/providers/Microsoft.Storage/storageAccounts/cascadeacct2?api-version=2023-01-01")
            .then().statusCode(403);
        given().when().delete(rg + "?api-version=2021-04-01")
            .then().statusCode(anyOf(is(200), is(202)));
    }
}
