/*
 *  Description: Unit tests for the ccproxy switch logic (no Spring / no DB). Every collaborator is
 *               mocked, so these pin the ORCHESTRATION MicroCloud owns: switch-to-ccproxy registers
 *               the machine when needed, waits until it can log in, starts the login, flips the
 *               machine to CCPROXY/PROVISIONING, and hands off to the background poller; switch-to-
 *               newapi restores the newapi env keys, frees the ccproxy account, and flips back to
 *               NEWAPI/READY. ccproxy owns the on-machine edits — MicroCloud only drives them.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.common.config.MicroCloudConfig
import app.microteams.microcloud.machine.instance.Machine
import app.microteams.microcloud.machine.instance.MachineRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.rucca.cheese.common.error.BadRequestError

class CcproxySwitchServiceTest {
    private val machineRepository = mockk<MachineRepository>(relaxed = true)
    private val ccproxyClient = mockk<CcproxyClient>(relaxed = true)
    private val newapiClient = mockk<NewapiClient>(relaxed = true)
    private val settingsSsh = mockk<MachineSettingsSsh>(relaxed = true)
    private val loginPoller = mockk<CcproxyLoginPoller>(relaxed = true)
    private val config = MicroCloudConfig().apply { newapi.machineBaseUrl = "http://host/newapi" }

    private val service =
        CcproxySwitchService(
            machineRepository,
            ccproxyClient,
            newapiClient,
            settingsSsh,
            loginPoller,
            config,
        )

    init {
        // save() is generic <S : Machine> S; a relaxed mock returns a bare Object that Kotlin's
        // inserted cast rejects. Echo the saved entity back.
        every { machineRepository.save(any<Machine>()) } answers { firstArg() }
    }

    private fun machine(id: Long = 1L, ccId: Long? = null) =
        Machine(
                tenantId = 1,
                customerId = 1,
                accountId = 1,
                hostname = "h",
                offeringId = 1,
                typeId = 1,
                templateId = 1,
                cores = 1,
                memoryMb = 512,
                diskGb = 4,
                placementId = 1,
                networkId = 1,
                loginUser = "dev",
                ip = "10.0.0.5",
            )
            .apply {
                this.id = id
                this.ccproxyMachineId = ccId
                this.newapiTokenId = 42
            }

    private fun cc(status: String, hasCredential: Boolean = false) =
        CcproxyMachine(99L, status, hasCredential, null, null, null)

    @Test
    fun switchToCcproxyRegistersWhenAbsentThenStartsLoginAndFlips() {
        val m = machine(ccId = null)
        every { machineRepository.findById(1L) } returns Optional.of(m)
        every { ccproxyClient.isConfigured() } returns true
        every { ccproxyClient.createMachine("10.0.0.5", "dev", 22, "h") } returns cc("provisioning")
        every { ccproxyClient.getMachine(99L) } returns cc("awaitingLogin")

        val result = service.switchToCcproxy(1L)

        assertEquals(99L, result.ccproxyMachineId)
        assertEquals(AiMode.CCPROXY, result.aiMode)
        assertEquals(AiStatus.PROVISIONING, result.aiStatus)
        verify { ccproxyClient.startLogin(99L) }
        verify { loginPoller.pollLoginToReady(1L, 99L) }
    }

    @Test
    fun switchToCcproxyReusesExistingRegistration() {
        val m = machine(ccId = 77L)
        every { machineRepository.findById(1L) } returns Optional.of(m)
        every { ccproxyClient.isConfigured() } returns true
        every { ccproxyClient.getMachine(77L) } returns cc("awaitingLogin")

        service.switchToCcproxy(1L)

        verify(exactly = 0) { ccproxyClient.createMachine(any(), any(), any(), any()) }
        verify { ccproxyClient.startLogin(77L) }
    }

    @Test
    fun switchToCcproxyRejectedWhenNotConfigured() {
        val m = machine()
        every { machineRepository.findById(1L) } returns Optional.of(m)
        every { ccproxyClient.isConfigured() } returns false
        assertThrows<BadRequestError> { service.switchToCcproxy(1L) }
    }

    @Test
    fun switchToNewapiRestoresEnvFreesAccountAndFlipsBack() {
        val m = machine(ccId = 77L)
        every { machineRepository.findById(1L) } returns Optional.of(m)
        every { newapiClient.isConfigured() } returns true
        every { newapiClient.revealKey(42) } returns "sk-key"

        val baseSlot = slot<String>()
        val keySlot = slot<String>()
        every {
            settingsSsh.restoreNewapiEnv(m, capture(baseSlot), capture(keySlot), any())
        } returns Unit

        val result = service.switchToNewapi(1L)

        assertEquals("http://host/newapi", baseSlot.captured)
        assertEquals("sk-key", keySlot.captured)
        verify { ccproxyClient.deleteMachine(77L) }
        assertNull(result.ccproxyMachineId)
        assertEquals(AiMode.NEWAPI, result.aiMode)
        assertEquals(AiStatus.READY, result.aiStatus)
    }
}
