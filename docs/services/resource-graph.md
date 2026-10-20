# Azure Resource Graph

floci-az answers `POST /providers/Microsoft.ResourceGraph/resources`, the query operation behind
`az graph query` and the Resource Graph SDKs. Response shapes follow the `2024-04-01` spec, and any
`api-version` is accepted.

> **HTTP-only, no Docker.** Queries read the emulator's live ARM state, so a resource is queryable as
> soon as its `PUT` returns.

## Tables

| Table | Rows |
|---|---|
| `Resources` | Every resource in the generic `/resources` listing of each queried subscription |
| `ResourceContainers` | The queried subscriptions and their resource groups |

Each row carries `id`, `name`, `type`, `tenantId`, `kind`, `location`, `resourceGroup`,
`subscriptionId`, `managedBy` and `tags`. `Resources` rows add `sku`, `plan`, `properties`,
`identity`, `zones` and `extendedLocation`. As in Azure, `type` is lowercase, so filter it with `=~`.
A resource whose listing entry carries no `properties` has `properties: null`.

## Request

- `query` is required. `subscriptions` limits the query to those subscriptions. Without it, the query
  covers every subscription of the caller's tenant. `managementGroups` is accepted and ignored.
- `options.$top` sets the page size (default 100, at most 1000). `options.$skip` starts at an offset.
  A response with more rows returns `$skipToken`, and sending it back in `options` returns the next page.
- `options.resultFormat: table` returns `columns` and `rows` instead of an object array.

The response carries `totalRecords`, `count`, `data`, an empty `facets` and `resultTruncated: "false"`.

## Query language

The query is a table name followed by `|`-separated operators from this subset of KQL:

| Operator | Form |
|---|---|
| `where` | `where <predicate>` |
| `project`, `extend` | `project name, env = tags.env` |
| `project-away` | `project-away properties, sku` |
| `take`, `limit` | `take 10` |
| `top` | `top 5 by name asc` |
| `order by`, `sort by` | `order by name asc, location desc`. The default order is descending, as in KQL |
| `count` | one row with `Count` |
| `distinct` | `distinct location` |
| `summarize` | `summarize count() by location` (column `count_`), `summarize n = count()`, `summarize by type` |

Predicates combine `and`, `or` and `not()`. The operators are `==`, `!=`, `=~`, `!~`, `contains`,
`contains_cs`, `startswith`, `endswith`, `has`, the negated forms (`!contains` and so on), `in`,
`!in`, `in~`, `!in~`, `<`, `>`, `<=` and `>=`. Dynamic columns are read with `tags.env` or
`tags['tofu-estate']`. The functions are `tolower`, `toupper`, `tostring`, `strlen`, `isempty`,
`isnotempty`, `isnull` and `isnotnull`.

Anything outside the subset, such as `join`, `mv-expand` or other aggregations, answers
`400 BadRequest` with an `InvalidQuery` detail and a `ParserFailure` detail naming the problem.

## Intentional deviations

- Facets are not computed, and `facets` is always empty.
- `$skipToken` is an opaque offset into the current result, so a page reflects writes made between calls.
- The `ResourceChanges`, `ResourcesHistory` and saved-query (`Microsoft.ResourceGraph/queries`) APIs are
  not served.

## Configuration

| Key | Environment variable | Default |
|---|---|---|
| `floci-az.services.resource-graph.enabled` | `FLOCI_AZ_SERVICES_RESOURCE_GRAPH_ENABLED` | `true` |
