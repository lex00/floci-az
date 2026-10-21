# Template deployments

floci-az serves `Microsoft.Resources/deployments`, the ARM template deployment provider behind
`az deployment group create`, `az deployment sub create`, the `azurerm_resource_group_template_deployment`
resource and the Resource Management SDKs. Shapes follow the `2024-03-01` spec, and any `api-version`
is accepted.

> **HTTP-only, no Docker.** Each template resource is provisioned by an internal `PUT` to the emulator's own
> handlers, so it lands exactly where a direct request would, and RBAC enforcement and policy evaluation apply
> to it as well.

## Endpoints

| Operation | Path |
|---|---|
| Create or update | `PUT {scope}/providers/Microsoft.Resources/deployments/{name}` |
| Get, check existence, delete | `GET`, `HEAD`, `DELETE` on the same path |
| List | `GET {scope}/providers/Microsoft.Resources/deployments` |
| Operations | `GET .../deployments/{name}/operations[/{operationId}]` |
| Validate | `POST .../deployments/{name}/validate` |
| Export template | `POST .../deployments/{name}/exportTemplate` |

`{scope}` is a resource group (`/subscriptions/{sub}/resourceGroups/{rg}`) or a subscription
(`/subscriptions/{sub}`). A subscription deployment needs a `location`, or the request fails with
`400 LocationRequired`. A resource-group deployment into a missing group answers `404 ResourceGroupNotFound`.

## How a deployment runs

The deployment runs to completion inside its `PUT`. The answer is `201` for a new name or `200` for an existing
one, carrying the final `provisioningState`. Clients that poll stop at once, since the state is already
terminal.

1. Parameters are bound: given values first, then `defaultValue`. A missing value, a value outside
   `allowedValues` or an undeclared parameter answers `400 InvalidTemplate`, and nothing is deployed.
2. Resources are expanded. Nested child `resources`, `copy` loops, `condition`, extension `scope`, symbolic
   names (`languageVersion` 2.0) and `existing` references are supported.
3. Resources are provisioned in `dependsOn` order, matched by resource id, `type/name`, name, symbolic name or
   copy loop name. A circular dependency answers `400 InvalidTemplate`.
4. Outputs are evaluated after every resource is in place, so `reference()` reads their live state.

A resource that fails stops the deployment, which ends `Failed` with `DeploymentFailed` and the resource's
own error under `details`. The `operations` list shows each step with its status code. In `Complete` mode,
the resource group's top-level resources that the template leaves out are deleted after a successful run.
Deleting a deployment removes its record and leaves its resources.

## Nested deployments

A `Microsoft.Resources/deployments` resource inside a template is provisioned like any other resource: a
`PUT` of that deployment at its target, which runs its template there and keeps its own record, operations
and outputs.

- The target is the resource group named by `resourceGroup`, in the subscription named by `subscriptionId`.
  With `subscriptionId` alone it is that subscription. A `scope` naming a subscription or resource group
  targets it too. With none of these, the target is the parent's own scope.
- `copy`, `condition` and `dependsOn` apply as for any resource, so a copy loop deploys into one resource
  group per iteration.
- With `expressionEvaluationOptions.scope: inner`, the nested `parameters` are evaluated in the parent and
  the nested template at the target. With the default `outer`, the nested template gets the parent's
  parameter values and variables.
- A nested deployment that fails fails the parent with `DeploymentFailed`. Its resources count as the
  parent's for `Complete` mode.

## Template functions

| Kind | Functions |
|---|---|
| Deployment | `parameters`, `variables`, `resourceGroup`, `subscription`, `tenant`, `deployment`, `environment` |
| Resources | `resourceId`, `subscriptionResourceId`, `tenantResourceId`, `extensionResourceId`, `reference`, `listKeys`, `listSecrets`, `listConnectionStrings` |
| Strings | `concat`, `format`, `toLower`, `toUpper`, `trim`, `replace`, `substring`, `split`, `join`, `startsWith`, `endsWith`, `indexOf`, `string`, `base64`, `base64ToString`, `uniqueString`, `guid`, `newGuid`, `utcNow` |
| Arrays and objects | `length`, `empty`, `contains`, `first`, `last`, `take`, `skip`, `createArray`, `createObject`, `union`, `range`, `coalesce`, `json` |
| Logic and numbers | `if`, `equals`, `not`, `and`, `or`, `less`, `lessOrEquals`, `greater`, `greaterOrEquals`, `add`, `sub`, `mul`, `div`, `mod`, `min`, `max`, `int`, `bool`, `copyIndex` |

Any other function answers `400 InvalidTemplate` naming it.

## Intentional deviations

- `uniqueString` and `guid` are deterministic and shaped like Azure's, with values of floci-az's own:
  Azure's hash is not reproduced.
- `templateLink`, `parametersLink`, Key Vault parameter references, what-if and template specs are not
  served. A nested deployment at management group or tenant scope is refused with `400 InvalidTemplate`.
- An `outer` nested template is evaluated at its target, so `resourceGroup()`, `deployment()` and
  `copyIndex()` of a parent copy loop answer for the nested deployment rather than the parent.
- Provisioning stops at the first failed resource, where Azure goes on with resources that do not depend
  on it.
- `providers` and `dependencies` in the response are empty.

## Configuration

| Key | Environment variable | Default |
|---|---|---|
| `floci-az.services.deployments.enabled` | `FLOCI_AZ_SERVICES_DEPLOYMENTS_ENABLED` | `true` |
