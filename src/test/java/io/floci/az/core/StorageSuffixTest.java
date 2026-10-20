package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(StorageSuffixTest.LocalSuffixProfile.class)
class StorageSuffixTest {

    public static class LocalSuffixProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.storage-suffix", "localhost:4577");
        }
    }

    @Test
    void metadataEndpointsReportTheConfiguredStorageSuffix() {
        given().when().get("/metadata/endpoints")
            .then().statusCode(200)
            .body("suffixes.storage", equalTo("localhost:4577"))
            .body("suffixes.keyVaultDns", equalTo("vault.azure.net"));
    }

    @Test
    void storageAccountEndpointsUseTheConfiguredSuffix() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/sub-suffix/resourceGroups/rg-suffix"
                    + "/providers/Microsoft.Storage/storageAccounts/suffixacct?api-version=2023-01-01")
            .then().statusCode(200)
            .body("properties.primaryEndpoints.blob", equalTo("http://suffixacct.blob.localhost:4577/"))
            .body("properties.primaryEndpoints.dfs", equalTo("http://suffixacct.dfs.localhost:4577/"))
            .body("properties.primaryEndpoints.queue", equalTo("http://suffixacct.queue.localhost:4577/"))
            .body("properties.primaryEndpoints.table", equalTo("http://suffixacct.table.localhost:4577/"));
    }

    @Test
    void hostStyleBlobRequestOnTheSuffixReachesTheAccount() {
        given().header("Host", "suffixblob.blob.localhost:4577")
            .when().put("/suffix-container?restype=container")
            .then().statusCode(201);

        given().when().get("/suffixblob/suffix-container?restype=container")
            .then().statusCode(200);
    }
}
