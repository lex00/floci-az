package io.floci.az.services.policy;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(PolicyEvaluationTest.EvaluateProfile.class)
class PolicyEvaluationTest {

    private static final String SUB = "/subscriptions/sub-policy-eval";
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String DEFINITIONS = SUB + AUTH + "/policyDefinitions";
    private static final String API = "?api-version=2023-04-01";

    public static class EvaluateProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.policy.evaluate", "true");
        }
    }

    private static void put(String path, String body, int status) {
        given().contentType("application/json").body(body).when().put(path + API).then().statusCode(status);
    }

    private static String group(String name) {
        String rg = SUB + "/resourceGroups/" + name;
        put(rg, "{\"location\":\"eastus\"}", 201);
        return rg;
    }

    private static void assign(String scope, String name, String definition, String extra) {
        put(scope + AUTH + "/policyAssignments/" + name,
                "{\"properties\":{\"policyDefinitionId\":\"" + DEFINITIONS + "/" + definition + "\"" + extra + "}}",
                201);
    }

    @Test
    void denyRefusesAStorageAccountWithoutTheEstateTag() {
        put(DEFINITIONS + "/require-estate", "{\"properties\":{\"mode\":\"Indexed\",\"parameters\":{\"tagName\":"
                + "{\"type\":\"String\",\"defaultValue\":\"estate\"}},\"policyRule\":{\"if\":{\"allOf\":["
                + "{\"field\":\"type\",\"equals\":\"Microsoft.Storage/storageAccounts\"},"
                + "{\"field\":\"[concat('tags[', parameters('tagName'), ']')]\",\"exists\":\"false\"}]},"
                + "\"then\":{\"effect\":\"deny\"}}}}", 201);
        String rg = group("rg-policy-deny");
        assign(rg, "require-estate", "require-estate",
                ",\"nonComplianceMessages\":[{\"message\":\"Tag the estate\"}]");

        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(rg + "/providers/Microsoft.Storage/storageAccounts/policyacct1?api-version=2023-01-01")
            .then().statusCode(403)
            .body("error.code", equalTo("RequestDisallowedByPolicy"))
            .body("error.target", equalTo("policyacct1"))
            .body("error.message", containsString("Tag the estate"))
            .body("error.additionalInfo[0].type", equalTo("PolicyViolation"))
            .body("error.additionalInfo[0].info.policyDefinitionEffect", equalTo("deny"));

        given().contentType("application/json").body("{\"location\":\"eastus\",\"tags\":{\"Estate\":\"e1\"}}")
            .when().put(rg + "/providers/Microsoft.Storage/storageAccounts/policyacct1?api-version=2023-01-01")
            .then().statusCode(200);

        given().contentType("application/json").body("{\"tags\":{\"other\":\"x\"}}")
            .when().patch(rg + "/providers/Microsoft.Storage/storageAccounts/policyacct1?api-version=2023-01-01")
            .then().statusCode(403);
    }

    @Test
    void denyActionRefusesDeletingATaggedResource() {
        put(DEFINITIONS + "/keep-estate", "{\"properties\":{\"mode\":\"All\",\"policyRule\":{\"if\":"
                + "{\"field\":\"tags['estate']\",\"equals\":\"locked\"},\"then\":{\"effect\":\"denyAction\","
                + "\"details\":{\"actionNames\":[\"delete\"]}}}}}", 201);
        String rg = group("rg-policy-delete");
        assign(rg, "keep-estate", "keep-estate", "");
        String locked = rg + "/providers/Microsoft.Storage/storageAccounts/policyacct2?api-version=2023-01-01";
        String free = rg + "/providers/Microsoft.Storage/storageAccounts/policyacct3?api-version=2023-01-01";
        given().contentType("application/json").body("{\"location\":\"eastus\",\"tags\":{\"estate\":\"locked\"}}")
            .when().put(locked).then().statusCode(200);
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(free).then().statusCode(200);

        given().when().delete(locked)
            .then().statusCode(403)
            .body("error.code", equalTo("RequestDisallowedByPolicy"))
            .body("error.message", containsString("Deletion of resource 'policyacct2'"));
        given().when().delete(free).then().statusCode(200);
    }

    @Test
    void exemptionsDoNotEnforceAndNotScopesLetRequestsThrough() {
        put(DEFINITIONS + "/deny-all-accounts", "{\"properties\":{\"mode\":\"All\",\"policyRule\":{\"if\":"
                + "{\"field\":\"type\",\"equals\":\"Microsoft.Storage/storageAccounts\"},"
                + "\"then\":{\"effect\":\"deny\"}}}}", 201);
        String exempt = group("rg-policy-exempt");
        String excluded = group("rg-policy-notscope");
        String relaxed = group("rg-policy-relaxed");
        assign(exempt, "deny-exempt", "deny-all-accounts", "");
        put(exempt + AUTH + "/policyExemptions/waive", "{\"properties\":{\"policyAssignmentId\":\"" + exempt + AUTH
                + "/policyAssignments/deny-exempt\",\"exemptionCategory\":\"Waiver\"}}", 201);
        assign(SUB, "deny-but-not-here", "deny-all-accounts", ",\"notScopes\":[\"" + excluded + "\"]");
        assign(relaxed, "deny-relaxed", "deny-all-accounts", ",\"enforcementMode\":\"DoNotEnforce\"");

        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(excluded + "/providers/Microsoft.Storage/storageAccounts/policyacct4?api-version=2023-01-01")
            .then().statusCode(200);
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(exempt + "/providers/Microsoft.Storage/storageAccounts/policyacct5?api-version=2023-01-01")
            .then().statusCode(403);
        given().when().delete(SUB + AUTH + "/policyAssignments/deny-but-not-here" + API).then().statusCode(200);
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(exempt + "/providers/Microsoft.Storage/storageAccounts/policyacct5?api-version=2023-01-01")
            .then().statusCode(200);
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(relaxed + "/providers/Microsoft.Storage/storageAccounts/policyacct6?api-version=2023-01-01")
            .then().statusCode(200);
    }

    @Test
    void aRuleItCannotReadFailsClosed() {
        put(DEFINITIONS + "/alias-rule", "{\"properties\":{\"mode\":\"All\",\"policyRule\":{\"if\":{\"allOf\":["
                + "{\"field\":\"type\",\"equals\":\"Microsoft.KeyVault/vaults\"},"
                + "{\"field\":\"Microsoft.KeyVault/vaults/enableRbacAuthorization\",\"equals\":false}]},"
                + "\"then\":{\"effect\":\"deny\"}}}}", 201);
        String rg = group("rg-policy-alias");
        assign(rg, "alias-rule", "alias-rule", "");

        given().contentType("application/json").body("{\"location\":\"eastus\",\"properties\":{\"tenantId\":\"t\"}}")
            .when().put(rg + "/providers/Microsoft.KeyVault/vaults/policy-kv1?api-version=2023-07-01")
            .then().statusCode(403)
            .body("error.message", containsString("cannot evaluate"));

        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(rg + "/providers/Microsoft.Storage/storageAccounts/policyacct7?api-version=2023-01-01")
            .then().statusCode(200);
    }
}
