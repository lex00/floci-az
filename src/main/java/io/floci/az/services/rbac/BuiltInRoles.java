package io.floci.az.services.rbac;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The built-in role definitions floci-az serves, with Azure's role ids and permissions as the Azure built-in
 * roles reference lists them. Data roles carry their data actions so a permissions check can read them.
 */
final class BuiltInRoles {

    record Permission(List<String> actions, List<String> notActions, List<String> dataActions,
                      List<String> notDataActions) {
    }

    record Role(String id, String roleName, String description, Permission permission) {
    }

    private static final String BLOB_CONTAINERS = "Microsoft.Storage/storageAccounts/blobServices/containers";

    static final List<Role> ALL = List.of(
            new Role("8e3af657-a8ff-443c-a75c-2fe8c4bcb635", "Owner",
                    "Grants full access to manage all resources, including the ability to assign roles in Azure RBAC.",
                    actions(List.of("*"), List.of())),
            new Role("b24988ac-6180-42a0-ab88-20f7382dd24c", "Contributor",
                    "Grants full access to manage all resources, but does not allow you to assign roles in Azure "
                            + "RBAC, manage assignments in Azure Blueprints, or share image galleries.",
                    actions(List.of("*"), List.of("Microsoft.Authorization/*/Delete", "Microsoft.Authorization/*/Write",
                            "Microsoft.Authorization/elevateAccess/Action", "Microsoft.Blueprint/blueprintAssignments/write",
                            "Microsoft.Blueprint/blueprintAssignments/delete", "Microsoft.Compute/galleries/share/action"))),
            new Role("acdd72a7-3385-48ef-bd42-f606fba81ae7", "Reader",
                    "View all resources, but does not allow you to make any changes.",
                    actions(List.of("*/read"), List.of())),
            new Role("18d7d88d-d35e-4fb5-a5c3-7773c20a72d9", "User Access Administrator",
                    "Lets you manage user access to Azure resources.",
                    actions(List.of("*/read", "Microsoft.Authorization/*", "Microsoft.Support/*"), List.of())),
            new Role("f58310d9-a9f6-439a-9e8d-f62e7b41a168", "Role Based Access Control Administrator",
                    "Manage access to Azure resources by assigning roles using Azure RBAC. This role does not allow "
                            + "you to manage access using other ways, such as Azure Policy.",
                    actions(List.of("Microsoft.Authorization/roleAssignments/write",
                            "Microsoft.Authorization/roleAssignments/delete", "*/read", "Microsoft.Support/*"),
                            List.of())),
            new Role("17d1049b-9a84-46fb-8f53-869881c3d3ab", "Storage Account Contributor",
                    "Permits management of storage accounts.",
                    actions(List.of("Microsoft.Authorization/*/read", "Microsoft.Resources/subscriptions/resourceGroups/read",
                            "Microsoft.Storage/storageAccounts/*", "Microsoft.Support/*"), List.of())),
            new Role("b7e6dc6d-f1e8-4753-8033-0f276bb0955b", "Storage Blob Data Owner",
                    "Provides full access to Azure Storage blob containers and data, including assigning POSIX "
                            + "access control.",
                    new Permission(List.of(BLOB_CONTAINERS + "/*",
                            "Microsoft.Storage/storageAccounts/blobServices/generateUserDelegationKey/action"),
                            List.of(), List.of(BLOB_CONTAINERS + "/blobs/*"), List.of())),
            new Role("ba92f5b4-2d11-453d-a403-e96b0029c9fe", "Storage Blob Data Contributor",
                    "Allows for read, write and delete access to Azure Storage blob containers and data.",
                    new Permission(List.of(BLOB_CONTAINERS + "/delete", BLOB_CONTAINERS + "/read",
                            BLOB_CONTAINERS + "/write",
                            "Microsoft.Storage/storageAccounts/blobServices/generateUserDelegationKey/action"),
                            List.of(), List.of(BLOB_CONTAINERS + "/blobs/delete", BLOB_CONTAINERS + "/blobs/read",
                            BLOB_CONTAINERS + "/blobs/write", BLOB_CONTAINERS + "/blobs/move/action",
                            BLOB_CONTAINERS + "/blobs/add/action"), List.of())),
            new Role("2a2b9908-6ea1-4ae2-8e65-a410df84e7d1", "Storage Blob Data Reader",
                    "Allows for read access to Azure Storage blob containers and data.",
                    new Permission(List.of(BLOB_CONTAINERS + "/read",
                            "Microsoft.Storage/storageAccounts/blobServices/generateUserDelegationKey/action"),
                            List.of(), List.of(BLOB_CONTAINERS + "/blobs/read"), List.of())),
            new Role("00482a5a-887f-4fb3-b363-3b7fe8e74483", "Key Vault Administrator",
                    "Perform all data plane operations on a key vault and all objects in it, including certificates, "
                            + "keys, and secrets. Cannot manage key vault resources or manage role assignments.",
                    new Permission(List.of("Microsoft.Authorization/*/read", "Microsoft.Insights/alertRules/*",
                            "Microsoft.Resources/deployments/*", "Microsoft.Resources/subscriptions/resourceGroups/read",
                            "Microsoft.Support/*", "Microsoft.KeyVault/checkNameAvailability/read",
                            "Microsoft.KeyVault/deletedVaults/read", "Microsoft.KeyVault/locations/*/read",
                            "Microsoft.KeyVault/vaults/*/read", "Microsoft.KeyVault/operations/read"),
                            List.of(), List.of("Microsoft.KeyVault/vaults/*"), List.of())),
            new Role("4633458b-17de-408a-b874-0445c86b69e6", "Key Vault Secrets User",
                    "Read secret contents. Only works for key vaults that use the 'Azure role-based access control' "
                            + "permission model.",
                    new Permission(List.of(), List.of(),
                            List.of("Microsoft.KeyVault/vaults/secrets/getSecret/action",
                                    "Microsoft.KeyVault/vaults/secrets/readMetadata/action"), List.of())),
            new Role("f1a07417-d97a-45cb-824c-7a7467783830", "Managed Identity Operator",
                    "Read and Assign User Assigned Identity",
                    actions(List.of("Microsoft.ManagedIdentity/userAssignedIdentities/*/read",
                            "Microsoft.ManagedIdentity/userAssignedIdentities/*/assign/action",
                            "Microsoft.Authorization/*/read", "Microsoft.Insights/alertRules/*",
                            "Microsoft.Resources/subscriptions/resourceGroups/read",
                            "Microsoft.Resources/deployments/*", "Microsoft.Support/*"), List.of())),
            new Role("7f951dda-4ed3-4680-a7ca-43fe172d538d", "AcrPull",
                    "acr pull",
                    actions(List.of("Microsoft.ContainerRegistry/registries/pull/read"), List.of())),
            new Role("8311e382-0749-4cb8-b61a-304f252e45ec", "AcrPush",
                    "acr push",
                    actions(List.of("Microsoft.ContainerRegistry/registries/pull/read",
                            "Microsoft.ContainerRegistry/registries/push/write"), List.of()))
    );

    private BuiltInRoles() {
    }

    static Optional<Role> byId(String id) {
        return ALL.stream().filter(role -> role.id().equalsIgnoreCase(id)).findFirst();
    }

    static Optional<Role> byName(String roleName) {
        String wanted = roleName.toLowerCase(Locale.ROOT);
        return ALL.stream().filter(role -> role.roleName().toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }

    private static Permission actions(List<String> actions, List<String> notActions) {
        return new Permission(actions, notActions, List.of(), List.of());
    }
}
