# Azure RBAC

floci-az serves Azure role-based access control under `Microsoft.Authorization`, with role definitions
and assignments plus the caller's permissions. These are the calls behind `az role`, the azurerm resources
`azurerm_role_definition` and `azurerm_role_assignment`, and the authorization SDKs. Shapes follow the
`2022-04-01` spec, and any `api-version` is accepted.

> **HTTP-only, no Docker.** RBAC state is in-memory, like the rest of the ARM control plane.

## Scopes

Every operation works at any scope, from the tenant root (`/`) down to a single resource. An assignment
on a resource sits under the resource's own path:

```
/subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Storage/storageAccounts/{name}/providers/Microsoft.Authorization/roleAssignments/{guid}
```

## Role definitions

These built-in roles are served with Azure's role ids and permissions:

| Role | Id |
|---|---|
| Owner | `8e3af657-a8ff-443c-a75c-2fe8c4bcb635` |
| Contributor | `b24988ac-6180-42a0-ab88-20f7382dd24c` |
| Reader | `acdd72a7-3385-48ef-bd42-f606fba81ae7` |
| User Access Administrator | `18d7d88d-d35e-4fb5-a5c3-7773c20a72d9` |
| Role Based Access Control Administrator | `f58310d9-a9f6-439a-9e8d-f62e7b41a168` |
| Storage Account Contributor | `17d1049b-9a84-46fb-8f53-869881c3d3ab` |
| Storage Blob Data Owner | `b7e6dc6d-f1e8-4753-8033-0f276bb0955b` |
| Storage Blob Data Contributor | `ba92f5b4-2d11-453d-a403-e96b0029c9fe` |
| Storage Blob Data Reader | `2a2b9908-6ea1-4ae2-8e65-a410df84e7d1` |
| Key Vault Administrator | `00482a5a-887f-4fb3-b363-3b7fe8e74483` |
| Key Vault Secrets User | `4633458b-17de-408a-b874-0445c86b69e6` |
| Managed Identity Operator | `f1a07417-d97a-45cb-824c-7a7467783830` |
| AcrPull | `7f951dda-4ed3-4680-a7ca-43fe172d538d` |
| AcrPush | `8311e382-0749-4cb8-b61a-304f252e45ec` |

Custom roles are created with `PUT .../roleDefinitions/{guid}`. A name another role already uses is
refused with `409 RoleDefinitionWithSameNameExists`, and a role that is still assigned cannot be deleted
(`409 RoleDefinitionHasAssignments`). The listing returns the built-ins plus the custom roles assignable at
the scope, and `$filter` accepts `roleName eq '...'` and `type eq 'BuiltInRole'` or `'CustomRole'`.

## Role assignments

`PUT .../roleAssignments/{guid}` stores `roleDefinitionId`, `principalId`, `principalType`, `description`,
`condition` and `conditionVersion`. It answers `201` on create.

- The name must be a GUID, or the request fails with `400 InvalidRoleAssignmentId`.
- An unknown role answers `400 RoleDefinitionDoesNotExist`.
- A second assignment of one role to one principal at one scope answers `409 RoleAssignmentExists`.
- A re-PUT that changes the principal, role or scope answers `400 RoleAssignmentUpdateNotPermitted`.
- A `condition` without a `conditionVersion` gets version `2.0`. Conditions are stored and returned, and
  never evaluated.

The listing at a scope returns the assignments at that scope, above it and below it. `$filter=atScope()`
keeps those at or above the scope, and `principalId eq '...'` or `assignedTo('...')` keeps one principal's.

## Permissions

`GET {scope}/providers/Microsoft.Authorization/permissions` returns what the caller's role assignments
allow at the scope: one entry per assignment at the scope or above it, with the role's `actions`,
`notActions`, `dataActions` and `notDataActions` and the assignment's condition. The caller is the `oid`
claim of the bearer token. Its signature is not checked, since ARM authentication is not enforced. A
request without a token holds nothing.

## Enforcement

With `floci-az.services.rbac.enforce` on, an ARM request whose bearer token carries an `oid` is held to
that principal's role assignments. floci-az names the request's operation as Azure does, such as
`Microsoft.Storage/storageAccounts/write` or `Microsoft.Resources/subscriptions/resourceGroups/read`. The
caller needs an assignment at the target's scope or above whose role lists a matching action and no
matching `notActions`. Otherwise the request is refused before its handler runs:

```json
{"error": {"code": "AuthorizationFailed", "message": "The client '{oid}' with object id '{oid}' does not have authorization to perform action 'Microsoft.Storage/storageAccounts/write' over scope '{scope}' or the scope is invalid. If access was recently granted, please refresh your credentials."}}
```

Requests without a token are not checked, and neither are the principals listed in
`floci-az.services.rbac.admin-principals`. Use either one to set up the estate and its assignments
before a held credential runs. The tenant and subscription listings stay open. Data actions and ABAC
conditions belong to the data plane, so they play no part in this check.

## Intentional deviations

- Principals are not looked up in Entra, so any `principalId` is accepted, and `principalType` defaults to
  `User` when the body leaves it out.
- `roleDefinitionId` is returned as sent.
- Changing or deleting a built-in role answers `403 AuthorizationFailed`.
- Deny assignments, role eligibility (PIM) and classic administrators are not served.

## Configuration

| Key | Environment variable | Default |
|---|---|---|
| `floci-az.services.rbac.enabled` | `FLOCI_AZ_SERVICES_RBAC_ENABLED` | `true` |
| `floci-az.services.rbac.enforce` | `FLOCI_AZ_SERVICES_RBAC_ENFORCE` | `false` |
| `floci-az.services.rbac.admin-principals` | `FLOCI_AZ_SERVICES_RBAC_ADMIN_PRINCIPALS` | none (comma-separated object ids) |
