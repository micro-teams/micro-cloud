/*
 *  Description: Unit tests for the role→permission grants. Pins the AI-switch authorization: a tenant
 *               may switch the AI mode of its OWN machines (actions scoped by the "owned" predicate),
 *               and the super-admin may switch any machine (no ownership predicate).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.authz

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rucca.cheese.auth.Permission

class RolePermissionServiceTest {
    private val service = RolePermissionService()

    private fun machinePermsWith(role: String, action: String): List<Permission> =
        service.getAuthorizationForUserWithRole(1L, role).permissions.filter {
            it.authorizedResource.types?.contains("machine") == true &&
                it.authorizedActions?.contains(action) == true
        }

    @Test
    fun tenantMaySwitchAiOnOwnedMachines() {
        for (action in listOf("switch-machine-ccproxy", "switch-machine-newapi")) {
            val perms = machinePermsWith(ROLE_TENANT, action)
            assertEquals(1, perms.size, "tenant should have exactly one grant for $action")
            assertEquals("owned", perms.single().customLogic, "$action must be scoped to owned")
        }
    }

    @Test
    fun superAdminMaySwitchAiOnAnyMachine() {
        for (action in listOf("switch-machine-ccproxy", "switch-machine-newapi")) {
            val perms = machinePermsWith(ROLE_SUPER_ADMIN, action)
            assertTrue(perms.isNotEmpty(), "super-admin should be able to $action")
            assertTrue(
                perms.any { it.customLogic == null },
                "super-admin's $action must not be ownership-scoped",
            )
        }
    }
}
