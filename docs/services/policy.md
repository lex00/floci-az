# Azure Policy

Compatible with the `Microsoft.Authorization` policy management plane used by the Azure CLI
(`az policy ...`), the `azurerm` Terraform provider (`azurerm_policy_definition`,
`azurerm_policy_set_definition`, `azurerm_*_policy_assignment`, `azurerm_*_policy_exemption`) and the
Azure SDKs (`azure-resourcemanager-resources`, `armpolicy`, `azure-mgmt-resource`). Covers **policy
definitions**, **policy set definitions** (initiatives), **policy assignments** and **policy
exemptions** at every scope the real service accepts.

> **HTTP-only, no Docker.** All policy state is in-memory and ephemeral, like the rest of the ARM
> control plane.

> **Control plane by default.** Definitions are stored and validated structurally. With
> `evaluate: true`, assignments refuse the ARM requests their `deny` and `denyAction` rules match (see
> [Evaluation](#evaluation)). Nothing is audited or remediated, and there is no compliance state
> (`Microsoft.PolicyInsights` is not implemented).

---

## Features

- **Policy definitions** at subscription and management-group scope: CreateOrUpdate, Get, Delete, List
  (`$filter=policyType eq 'Custom'` and `atExactScope()` are honoured). `policyType` is always
  `Custom`, `mode` defaults to `Indexed`, `version` defaults to `1.0.0`, and `metadata` gains the
  `createdBy` / `createdOn` / `updatedBy` / `updatedOn` stamps Azure adds
- **Policy set definitions** at subscription and management-group scope: CreateOrUpdate, Get, Delete,
  List. Each reference gets a server-generated `policyDefinitionReferenceId` when none is supplied and
  a `definitionVersion` of `1.*.*`
- **Policy assignments** at management-group, subscription, resource-group and resource scope:
  Create, Get, Update (PATCH of `identity`, `location`, `resourceSelectors`, `overrides`), Delete
  (returns the deleted assignment), List for subscription / resource group / resource / management
  group with `atScope()`, `atExactScope()`, `atScopeAndBelow()` and `policyDefinitionId eq '...'`.
  `scope`, `enforcementMode`, `definitionVersion` and `instanceId` are populated server-side;
  a `SystemAssigned` identity receives a stable `principalId`, and `UserAssigned` identities resolve
  their `principalId` / `clientId` from [Managed Identity](managed-identity.md) when the identity exists
- **Policy exemptions** at the same scopes: CreateOrUpdate, Get, Update (PATCH of
  `resourceSelectors`, `assignmentScopeValidation`), Delete (returns the deleted exemption), List with
  `atScope()`, `atExactScope()`, `excludeExpired()` and `policyAssignmentId eq '...'`. Exemptions are
  deleted together with their assignment
- **Referential integrity**: assignments and set definitions must reference definitions that exist
  (`PolicyDefinitionNotFound` / `PolicySetDefinitionNotFound`), exemptions must reference an existing
  assignment (`PolicyAssignmentNotFound`), and a definition or set definition that is still referenced
  cannot be deleted (`InvalidDeletePolicyDefinitionRequest` / `InvalidDeletePolicySetDefinitionRequest`)
- **Azure error shapes**: `PolicyDefinitionNotFound`, `PolicySetDefinitionNotFound`,
  `PolicyAssignmentNotFound`, `PolicyExemptionNotFound`, `InvalidPolicyRule`,
  `InvalidCreatePolicySetDefinitionRequest`, `InvalidPolicyDefinitionReference`, `MissingSubscription`
  for tenant-level writes, and ARM's `InvalidRequestContent` for malformed JSON

---

## Endpoints

`{scope}` is empty (tenant root), `providers/Microsoft.Management/managementGroups/{mg}`,
`subscriptions/{sub}`, `subscriptions/{sub}/resourceGroups/{rg}` or a full resource id.

```
# Definitions and set definitions: subscription or management-group scope
PUT    /{scope}/providers/Microsoft.Authorization/policyDefinitions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyDefinitions/{name}
DELETE /{scope}/providers/Microsoft.Authorization/policyDefinitions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyDefinitions[?$filter=...]
PUT    /{scope}/providers/Microsoft.Authorization/policySetDefinitions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policySetDefinitions/{name}
DELETE /{scope}/providers/Microsoft.Authorization/policySetDefinitions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policySetDefinitions[?$filter=...]

# Built-in listings at the tenant root (always empty, see deviations)
GET    /providers/Microsoft.Authorization/policyDefinitions
GET    /providers/Microsoft.Authorization/policySetDefinitions

# Assignments and exemptions: management group, subscription, resource group or resource scope
PUT    /{scope}/providers/Microsoft.Authorization/policyAssignments/{name}
PATCH  /{scope}/providers/Microsoft.Authorization/policyAssignments/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyAssignments/{name}
DELETE /{scope}/providers/Microsoft.Authorization/policyAssignments/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyAssignments[?$filter=...]
PUT    /{scope}/providers/Microsoft.Authorization/policyExemptions/{name}
PATCH  /{scope}/providers/Microsoft.Authorization/policyExemptions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyExemptions/{name}
DELETE /{scope}/providers/Microsoft.Authorization/policyExemptions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyExemptions[?$filter=...]
```

Any `api-version` is accepted. Shapes follow the policy spec `2025-03-01` and the exemptions spec
`2022-07-01-preview`.

---

## Quickstart

### 1. Create a definition and assign it to a resource group

```bash
BASE=http://localhost:4577
SUB=00000000-0000-0000-0000-000000000001

curl -s -X PUT "$BASE/subscriptions/$SUB/providers/Microsoft.Authorization/policyDefinitions/allowed-locations?api-version=2025-03-01" \
  -H "Content-Type: application/json" \
  -d '{"properties":{"displayName":"Allowed locations","mode":"All",
       "policyRule":{"if":{"field":"location","notIn":["eastus"]},"then":{"effect":"deny"}}}}'

curl -s -X PUT "$BASE/subscriptions/$SUB/resourceGroups/my-rg/providers/Microsoft.Authorization/policyAssignments/enforce-locations?api-version=2025-03-01" \
  -H "Content-Type: application/json" \
  -d "{\"location\":\"eastus\",\"identity\":{\"type\":\"SystemAssigned\"},
       \"properties\":{\"policyDefinitionId\":\"/subscriptions/$SUB/providers/Microsoft.Authorization/policyDefinitions/allowed-locations\"}}"
```

```json
{
  "identity": {"principalId": "4f0c…", "tenantId": "00000000-0000-0000-0000-000000000002", "type": "SystemAssigned"},
  "properties": {
    "policyDefinitionId": "/subscriptions/…/providers/Microsoft.Authorization/policyDefinitions/allowed-locations",
    "definitionVersion": "1.*.*",
    "scope": "/subscriptions/…/resourceGroups/my-rg",
    "metadata": {"createdBy": "…", "createdOn": "2026-09-04T10:00:00Z"},
    "enforcementMode": "Default",
    "instanceId": "…"
  },
  "id": "/subscriptions/…/resourceGroups/my-rg/providers/Microsoft.Authorization/policyAssignments/enforce-locations",
  "type": "Microsoft.Authorization/policyAssignments",
  "name": "enforce-locations",
  "location": "eastus"
}
```

### 2. Azure CLI

With the CLI pointed at the emulator (see the `compat-azcli` suite for the custom-cloud setup):

```bash
az policy definition create --name allowed-locations --mode All \
  --rules '{"if":{"field":"location","notIn":["eastus"]},"then":{"effect":"deny"}}'
az policy assignment create --name enforce-locations --policy allowed-locations -g my-rg
az policy exemption create --name legacy -g my-rg --exemption-category Waiver \
  --policy-assignment "$(az policy assignment show -n enforce-locations -g my-rg --query id -o tsv)"
az policy assignment list -g my-rg
```

### 3. Terraform

```hcl
resource "azurerm_policy_definition" "allowed_locations" {
  name         = "allowed-locations"
  policy_type  = "Custom"
  mode         = "All"
  display_name = "Allowed locations"
  policy_rule  = jsonencode({
    if   = { field = "location", notIn = ["eastus"] }
    then = { effect = "deny" }
  })
}

resource "azurerm_resource_group_policy_assignment" "enforce" {
  name                 = "enforce-locations"
  resource_group_id    = azurerm_resource_group.example.id
  policy_definition_id = azurerm_policy_definition.allowed_locations.id
}
```

---

## Configuration

```yaml
floci-az:
  services:
    policy:
      enabled: true
      evaluate: false
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_POLICY_ENABLED` | `true` | Enables the `Microsoft.Authorization` policy resources. When disabled, their paths fall through to the generic ARM handler and answer `404` |
| `evaluate` | `FLOCI_AZ_SERVICES_POLICY_EVALUATE` | `false` | Evaluates `deny` and `denyAction` rules against ARM requests, as described under [Evaluation](#evaluation) |

---

## Evaluation

With `evaluate: true`, every assignment whose scope covers an ARM request's target is applied before the
request reaches its handler. `notScopes`, `enforcementMode: DoNotEnforce` and unexpired exemptions each
take a target out of an assignment. An exemption that
lists `policyDefinitionReferenceIds` skips only those definitions of an initiative.

- `deny` refuses a `PUT` or `PATCH` that the rule's `if` matches. A `PUT` is judged on its body, and a `PATCH`
  on the resource it would leave: the stored resource with the body's fields over it.
- `denyAction` with `delete` in `details.actionNames` refuses a `DELETE` of a resource the rule matches.
  Deleting a resource group is refused too when such a rule matches a resource in it, unless the rule's
  `details.cascadeBehaviors.resourceGroup` is `allow`. The default is `deny`, as in Azure.
- Other effects never block a request, so they are not evaluated.
- `Indexed` definitions evaluate only the types that take tags and a location. They skip resource groups,
  extension resources and nested child types such as subnets, except child types that carry both, such as
  SQL databases and web app slots. Data-plane modes (`Microsoft.KeyVault.Data` and the rest) are skipped.

A refused request answers `403 RequestDisallowedByPolicy`. The message carries the assignment's
non-compliance message when it has one. `additionalInfo` holds a `PolicyViolation` entry that names the
assignment and definition.

The rule language is a subset:

| Part | Supported |
|---|---|
| Logical operators | `allOf`, `anyOf`, `not` |
| Conditions | `field` or `value` with `equals`, `notEquals`, `like`, `notLike`, `match`, `notMatch`, `matchInsensitively`, `notMatchInsensitively`, `contains`, `notContains`, `in`, `notIn`, `containsKey`, `notContainsKey`, `less`, `lessOrEquals`, `greater`, `greaterOrEquals`, `exists` |
| Fields | `type`, `name`, `fullName`, `id`, `kind`, `location`, `tags`, `tags['key']`, `tags[key]`, `tags.key` |
| Template functions | `parameters`, `field`, `concat`, `toLower`, `toUpper` |

String comparisons ignore case, tag names match in any case, and a missing field compares as an empty
string. Parameters take the assignment's values over the definition's defaults. Within an initiative,
the set's parameters feed each reference.

**Unreadable rules fail closed.** An alias, a `count` expression, another template function, or a definition
floci-az does not hold (such as a built-in) refuses the request with `RequestDisallowedByPolicy`. The message
says floci-az cannot evaluate the policy. This happens only when evaluation reaches the unreadable part, so a
rule whose `type` check fails first lets other types through.

---

## Intentional deviations

- **Deny only, and no compliance data.** Rules are evaluated only under `evaluate: true`, and only the
  `deny` and `denyAction` effects act. `audit` / `modify` / `deployIfNotExists` effects do nothing, and
  `Microsoft.PolicyInsights` (policy states, remediations, compliance summaries) is not implemented.
- **No built-in definitions.** Azure ships thousands of built-in definitions and initiatives; none are
  seeded. Tenant-rooted listings return an empty collection, tenant-rooted reads return
  `PolicyDefinitionNotFound` / `PolicySetDefinitionNotFound`, and built-in ids
  (`/providers/Microsoft.Authorization/policyDefinitions/{guid}` and
  `/providers/Microsoft.Authorization/policySetDefinitions/{guid}`) are accepted in assignments and set
  definitions without validation. Every other reference, including relative ids and ids under another
  provider namespace, must name an existing definition or set definition.
- **Management groups are scopes, not resources.** `Microsoft.Management/managementGroups/{mg}` is
  accepted as a scope for definitions, set definitions, assignments and exemptions, but management
  groups themselves are not modelled: any id is accepted, there is no hierarchy, and a
  management-group assignment does not appear in subscription-scoped listings.
- **Policy rules are validated structurally only.** A rule must be an object with an `if` condition
  and a `then` block that names an `effect`; aliases, functions, parameter references and effect
  details are not checked. Assignment parameters are not validated against the definition's
  parameter schema.
- **Exemption scope is not validated** against the assignment's scope (`assignmentScopeValidation` is
  stored and echoed only).
- **Definition versions are not implemented.** `versions` sub-resources answer `404`; `version` and
  `definitionVersion` are stored and echoed as plain strings.
- **`systemData` and `metadata` audit fields** name a fixed emulator identity rather than the caller,
  and `metadata.updatedBy` / `updatedOn` appear only after the first update instead of being present
  as `null` from creation.
- **Listings are unpaginated** (`nextLink` is never returned) and `$top` is ignored.
- **Policy state is in-memory only**, regardless of `storage.mode`, like the rest of the ARM control
  plane.
