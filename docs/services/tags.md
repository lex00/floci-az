# Tags API

floci-az serves `Microsoft.Resources/tags/default`, the Tags API behind `az tag update`, `az tag create
--resource-id`, the `azurerm` resources that tag through it, and the Resource Management SDKs' `TagsOperations`.
Shapes follow the `2021-04-01` spec, and any `api-version` is accepted.

> **HTTP-only, no Docker.** A resource group or resource keeps its tags in its own handler. The Tags API reads
> the target with an internal `GET` and writes its new tag set with an internal `PATCH`, or a `PUT` for a handler
> that serves no `PATCH`, so the target's reads and Resource Graph report the result. RBAC enforcement and policy
> evaluation apply to that write as well.

## Endpoints

| Operation | Path |
|---|---|
| Get | `GET {scope}/providers/Microsoft.Resources/tags/default` |
| Create or replace | `PUT` on the same path, body `{"properties":{"tags":{...}}}` |
| Update | `PATCH` on the same path, body `{"operation":"Merge" \| "Replace" \| "Delete","properties":{"tags":{...}}}` |
| Delete | `DELETE` on the same path |

`{scope}` is a subscription (`/subscriptions/{sub}`), a resource group, or any resource id. Every operation
answers `200` with the scope's tags:

```json
{
  "id": "{scope}/providers/Microsoft.Resources/tags/default",
  "name": "default",
  "type": "Microsoft.Resources/tags",
  "properties": { "tags": { "tofu-estate": "e1" } }
}
```

## Operations

- `Merge` sets each given tag and keeps the rest. A name given in another spelling replaces the existing one,
  since tag names are case-insensitive.
- `Replace` and `PUT` make the given tags the whole set.
- `Delete` removes each given name; a given non-empty value removes the tag only when its value matches.
- `DELETE` removes every tag.

An unknown `operation` answers `400 InvalidRequestContent`. A missing resource group answers
`404 ResourceGroupNotFound`, and a missing resource `404 ResourceNotFound`.

## Subscription tags

Tags at subscription scope are reported by `GET /subscriptions/{sub}`, `GET /subscriptions` and the
`resourcecontainers` table of [Resource Graph](resource-graph.md). They are kept in memory only.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_TAGS_ENABLED` | `true` | Serve the Tags API |
