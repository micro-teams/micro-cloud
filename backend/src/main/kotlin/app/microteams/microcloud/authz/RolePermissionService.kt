/*
 *  Description: The whole authorization matrix in one place. Two roles:
 *               - super-admin: platform operator; manages tenants and platform config.
 *               - tenant: an upstream deployment; manages its own customers/accounts/machines,
 *                 scoped to itself.
 *               A JWT carries the role's expanded permission set; @Guard evaluates an endpoint's
 *               (action, resourceType) against it, with predicates registered by the controllers.
 *               Authorization code has no business logic; business code has no authorization.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.authz

import org.rucca.cheese.auth.Authorization
import org.rucca.cheese.auth.AuthorizedResource
import org.rucca.cheese.auth.Permission
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service

const val ROLE_SUPER_ADMIN = "super-admin"
const val ROLE_TENANT = "tenant"

@Service
class RolePermissionService {
    fun getAuthorizationForUserWithRole(userId: IdType, role: String): Authorization =
        when (role) {
            ROLE_SUPER_ADMIN -> superAdmin(userId)
            ROLE_TENANT -> tenant(userId)
            else -> throw IllegalArgumentException("Role '$role' is not supported")
        }

    /** The platform operator: full control over tenants and platform config. */
    private fun superAdmin(userId: IdType): Authorization =
        Authorization(
            userId = userId,
            permissions =
                listOf(
                    grant(listOf("query"), "ping"),
                    grant(
                        listOf(
                            "list-tenants",
                            "create-tenant",
                            "get-tenant",
                            "update-tenant",
                            "delete-tenant",
                            "list-tenant-secrets",
                            "create-tenant-secret",
                            "revoke-tenant-secret",
                        ),
                        "tenant",
                    ),
                    grant(
                        listOf(
                            "list-proxmox",
                            "create-proxmox",
                            "get-proxmox",
                            "update-proxmox",
                            "delete-proxmox",
                            "inventory-proxmox",
                        ),
                        "proxmox",
                    ),
                    grant(
                        listOf(
                            "list-placement",
                            "create-placement",
                            "get-placement",
                            "update-placement",
                            "delete-placement",
                        ),
                        "placement",
                    ),
                    grant(
                        listOf(
                            "list-network",
                            "create-network",
                            "get-network",
                            "update-network",
                            "delete-network",
                        ),
                        "network",
                    ),
                    grant(
                        listOf(
                            "list-machine-type",
                            "get-machine-type",
                            "create-machine-type",
                            "update-machine-type",
                            "delete-machine-type",
                        ),
                        "machine-type",
                    ),
                    grant(
                        listOf(
                            "list-zone",
                            "get-zone",
                            "create-zone",
                            "update-zone",
                            "delete-zone",
                        ),
                        "zone",
                    ),
                    grant(
                        listOf("list-template", "list-template-upload", "upload-template"),
                        "template",
                    ),
                    grant(
                        listOf(
                            "list-offering",
                            "create-offering",
                            "get-offering",
                            "update-offering",
                            "delete-offering",
                        ),
                        "offering",
                    ),
                ),
        )

    /** A tenant: scoped to its own resources (customer/account/machine rules land here later). */
    private fun tenant(userId: IdType): Authorization =
        Authorization(
            userId = userId,
            permissions =
                listOf(
                    grant(listOf("query"), "ping"),
                    grant(listOf("create-customer"), "customer"),
                    // Enumeration = composable optional filters; the tenant scope is a mandatory
                    // condition enforced by a predicate (here: no cross-tenant reference exists
                    // yet).
                    grant(listOf("list-customers"), "customer", "is-enumerating-own-customers"),
                    grant(listOf("get-customer", "delete-customer"), "customer", "owned"),
                    grant(listOf("create-account"), "account"),
                    // Filtering accounts by customer requires that customer to belong to the
                    // tenant.
                    grant(listOf("list-accounts"), "account", "queried-customer-is-own"),
                    grant(
                        listOf("get-account", "topup-account", "list-account-ledger"),
                        "account",
                        "owned",
                    ),
                    // A tenant's catalog is the offerings it was granted (each carries the machine
                    // type spec ranges, zone, and template it needs) — no separate
                    // type/zone/template
                    // reads.
                    grant(listOf("list-offering"), "offering"),
                    // Machines: create for the tenant; a customer_id filter must be the tenant's
                    // own.
                    grant(listOf("create-machine"), "machine"),
                    grant(listOf("list-machine"), "machine", "queried-customer-is-own-machine"),
                    grant(
                        listOf("get-machine", "start-machine", "stop-machine", "delete-machine"),
                        "machine",
                        "owned",
                    ),
                ),
        )

    private fun grant(actions: List<String>, resourceType: String, customLogic: String? = null) =
        Permission(
            authorizedActions = actions,
            authorizedResource = AuthorizedResource(types = listOf(resourceType)),
            customLogic = customLogic,
        )
}
