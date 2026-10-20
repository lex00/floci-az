package io.floci.az.services.resourcegraph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KqlQueryTest {

    private static final List<Map<String, Object>> ROWS = List.of(
            Map.of("name", "alpha", "type", "microsoft.storage/storageaccounts", "location", "eastus",
                    "tags", Map.of("env", "Prod", "tofu-estate", "e1"), "properties", Map.of("size", 3)),
            Map.of("name", "beta", "type", "microsoft.keyvault/vaults", "location", "westus",
                    "tags", Map.of("env", "dev"), "properties", Map.of("size", 1)),
            Map.of("name", "gamma", "type", "microsoft.storage/storageaccounts", "location", "eastus",
                    "tags", Map.of(), "properties", Map.of("size", 2)));

    private static List<Object> names(String query) {
        return KqlQuery.parse(query).run(ROWS).stream().map(row -> row.get("name")).toList();
    }

    @Test
    void whereMatchesTagValuesCaseInsensitivelyWithTildeEquals() {
        assertThat(names("Resources | where tags['env'] =~ 'prod'"), contains("alpha"));
        assertThat(names("Resources | where tags.env == 'prod'"), hasSize(0));
        assertThat(names("Resources | where tags['tofu-estate'] == 'e1' and location == 'eastus'"),
                contains("alpha"));
    }

    @Test
    void typeFiltersAcceptTheCasingClientsSend() {
        assertThat(names("Resources | where type =~ 'Microsoft.Storage/storageAccounts' | order by name asc"),
                contains("alpha", "gamma"));
        assertThat(names("Resources | where type in~ ('Microsoft.KeyVault/vaults') or name startswith 'gam'"),
                contains("beta", "gamma"));
        assertThat(names("Resources | where name !in ('alpha', 'beta')"), contains("gamma"));
    }

    @Test
    void emptinessFunctionsAndNumericComparisonsWork() {
        assertThat(names("Resources | where isnotempty(tags.env)"), contains("alpha", "beta"));
        assertThat(names("Resources | where isempty(tags.env)"), contains("gamma"));
        assertThat(names("Resources | where properties.size >= 2 | sort by properties.size desc"),
                contains("alpha", "gamma"));
    }

    @Test
    void projectTakeAndTopShapeTheRows() {
        List<Map<String, Object>> rows = KqlQuery.parse(
                "Resources | project name, env = tags.env, size = properties.size | top 2 by size").run(ROWS);
        assertThat(rows, hasSize(2));
        assertThat(rows.getFirst(), equalTo(Map.of("name", "alpha", "env", "Prod", "size", 3)));
        assertThat(names("Resources | take 1"), hasSize(1));
    }

    @Test
    void summarizeAndCountAggregate() {
        List<Map<String, Object>> byLocation = KqlQuery.parse(
                "Resources | summarize count() by location | order by count_ desc").run(ROWS);
        assertThat(byLocation.getFirst(), equalTo(Map.of("location", "eastus", "count_", 2L)));
        assertThat(KqlQuery.parse("Resources | count").run(ROWS), contains(Map.of("Count", 3L)));
        assertThat(KqlQuery.parse("Resources | summarize by location").run(ROWS), hasSize(2));
    }

    @Test
    void unsupportedSyntaxIsRejected() {
        assertThrows(KqlQuery.KqlException.class, () -> KqlQuery.parse("Resources | join kind=inner (x) on id"));
        assertThrows(KqlQuery.KqlException.class, () -> KqlQuery.parse("Resources | where name == 'open"));
        assertThrows(KqlQuery.KqlException.class, () -> KqlQuery.parse("Resources | where"));
    }
}
