/*
 *  Description: This file implements the RolePermissionService class — the whole business
 *               authorization model of this backend, in one table.
 *
 *               That claim is the point, and it only holds if two rules are kept absolutely:
 *
 *                 1. Authorization code has no business logic. Every clause below is an atomic,
 *                    named predicate whose meaning is obvious from its name; each is registered in
 *                    the @PostConstruct of the controller that owns the concept, and does nothing
 *                    but turn (userId, authInfo) into one true/false fact.
 *                 2. Business code has no authorization. No service throws ForbiddenError and no
 *                    service filters by "may this user...". If a rule is not visible here, it does
 *                    not exist — an auditor must never have to read a service to learn who may do
 *                    what.
 *
 *               Actions are named after what the endpoint *does* ("rename-machine", "watch",
 *               "post-message"), not after CRUD, so a row reads as the permission of a specific
 *               endpoint rather than of a vague verb. customLogic is a boolean expression over
 *               those predicates (&&, ||, !, parens — see CustomAuthLogics), so a rule with several
 *               ways to be satisfied stays one readable row instead of being smeared across
 *               several permissions.
 *
 *               A permission grants an action when all of its clauses match; the matrix grants it
 *               when any permission does.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *      nameisyui
 *
 */

package app.microteams.microcloud.authz

import org.rucca.cheese.auth.Authorization
import org.rucca.cheese.auth.AuthorizedResource
import org.rucca.cheese.auth.Permission
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service

@Service
class RolePermissionService {
    fun getAuthorizationForUserWithRole(userId: IdType, role: String): Authorization {
        return when (role) {
            "standard-user" -> getAuthorizationForStandardUser(userId)
            else -> throw IllegalArgumentException("Role '$role' is not supported")
        }
    }

    // The original space/task/ai:quota entries were removed along with the business modules they
    // gated. See ~/work/ref-study/microteams-backend-mt for the full original set — the auth
    // framework
    // itself (AuthorizationService, AuthorizationAspect, the Permission/AuthorizedResource model,
    // custom-logic expressions) is untouched and still the pattern followed here.
    fun getAuthorizationForStandardUser(userId: IdType): Authorization {
        return Authorization(
            userId = userId,
            permissions =
                listOf(
                    // Minimal auth-chain smoke-test endpoint.
                    Permission(
                        authorizedActions = listOf("query"),
                        authorizedResource = AuthorizedResource(types = listOf("ping")),
                    )
                ),
        )
    }
}
