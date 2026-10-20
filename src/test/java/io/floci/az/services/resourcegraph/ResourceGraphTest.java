package io.floci.az.services.resourcegraph;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ResourceGraphTest {

    private static final String SUB = "sub-resgraph";
    private static final String RG = "/subscriptions/" + SUB + "/resourceGroups/rg-resgraph";
    private static final String GRAPH = "/providers/Microsoft.ResourceGraph/resources?api-version=2024-04-01";

    @BeforeEach
    void createResources() {
        given().contentType("application/json").body("{\"location\":\"eastus\",\"tags\":{\"team\":\"core\"}}")
            .when().put(RG + "?api-version=2021-04-01");
        for (String name : List.of("graphacct1", "graphacct2", "graphacct3")) {
            given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"tags\":{\"estate\":\"" + (name.endsWith("3") ? "Other" : "Main")
                        + "\"}}")
                .when().put(RG + "/providers/Microsoft.Storage/storageAccounts/" + name + "?api-version=2023-01-01");
        }
    }

    private static String body(String query, String options) {
        return "{\"subscriptions\":[\"" + SUB + "\"],\"query\":\"" + query + "\""
                + (options == null ? "" : ",\"options\":" + options) + "}";
    }

    @Test
    void queryFiltersResourcesByTagAndProjectsColumns() {
        given().contentType("application/json")
            .body(body("Resources | where type =~ 'Microsoft.Storage/storageAccounts' and tags.estate =~ 'main' "
                    + "| project name, type, resourceGroup, tags | order by name asc", null))
            .when().post(GRAPH)
            .then().statusCode(200)
            .body("totalRecords", equalTo(2))
            .body("count", equalTo(2))
            .body("data.name", equalTo(List.of("graphacct1", "graphacct2")))
            .body("data[0].type", equalTo("microsoft.storage/storageaccounts"))
            .body("data[0].resourceGroup", equalTo("rg-resgraph"))
            .body("resultTruncated", equalTo("false"))
            .body("facets", hasSize(0))
            .body("$skipToken", nullValue());
    }

    @Test
    void pagesFollowTheSkipToken() {
        String query = "Resources | where name startswith 'graphacct' | project name | order by name asc";
        String token = given().contentType("application/json").body(body(query, "{\"$top\":2}"))
            .when().post(GRAPH)
            .then().statusCode(200)
            .body("totalRecords", equalTo(3))
            .body("data.name", equalTo(List.of("graphacct1", "graphacct2")))
            .body("$skipToken", notNullValue())
            .extract().path("$skipToken");

        given().contentType("application/json").body(body(query, "{\"$top\":2,\"$skipToken\":\"" + token + "\"}"))
            .when().post(GRAPH)
            .then().statusCode(200)
            .body("data.name", equalTo(List.of("graphacct3")))
            .body("$skipToken", nullValue());
    }

    @Test
    void resourceContainersListsTheSubscriptionsGroups() {
        given().contentType("application/json")
            .body(body("ResourceContainers | where type == 'microsoft.resources/subscriptions/resourcegroups' "
                    + "| where tags.team == 'core' | project name, location", null))
            .when().post(GRAPH)
            .then().statusCode(200)
            .body("data.name", hasItem("rg-resgraph"));
    }

    @Test
    void tableFormatReturnsColumnsAndRows() {
        given().contentType("application/json")
            .body(body("Resources | where name == 'graphacct1' | project name, location",
                    "{\"resultFormat\":\"table\"}"))
            .when().post(GRAPH)
            .then().statusCode(200)
            .body("data.columns.name", equalTo(List.of("name", "location")))
            .body("data.columns[0].type", equalTo("string"))
            .body("data.rows[0]", equalTo(List.of("graphacct1", "eastus")));
    }

    @Test
    void invalidQueryIsABadRequest() {
        given().contentType("application/json").body(body("Resources | frobnicate", null))
            .when().post(GRAPH)
            .then().statusCode(400)
            .body("error.code", equalTo("BadRequest"))
            .body("error.details[0].code", equalTo("InvalidQuery"));
    }
}
