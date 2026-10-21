package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestProfile(KeyVaultSuffixTest.LocalSuffixProfile.class)
class KeyVaultSuffixTest {

    public static class LocalSuffixProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.key-vault-suffix", "vault.localhost:4577");
        }
    }

    @Test
    void metadataEndpointsReportTheConfiguredKeyVaultSuffix() {
        given().when().get("/metadata/endpoints")
            .then().statusCode(200)
            .body("suffixes.keyVaultDns", equalTo("vault.localhost:4577"));
    }

    @Test
    void vaultUriUsesTheConfiguredSuffix() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/sub-kvsuffix/resourceGroups/rg-kvsuffix"
                    + "/providers/Microsoft.KeyVault/vaults/suffixvault?api-version=2023-07-01")
            .then().statusCode(200)
            .body("properties.vaultUri", equalTo("https://suffixvault.vault.localhost:4577/"));
    }

    @Test
    void secretSetOnTheSuffixHostCarriesAnIdUnderTheSuffix() {
        given().header("Host", "suffixvault.vault.localhost:4577")
            .header("Authorization", "Bearer token")
            .contentType("application/json").body("{\"value\":\"s3cret\"}")
            .when().put("/secrets/suffix-secret?api-version=7.4")
            .then().statusCode(200)
            .body("id", startsWith("https://suffixvault.vault.localhost:4577/secrets/suffix-secret/"));
    }
}
