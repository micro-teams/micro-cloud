package app.microteams.microcloud.api

import app.microteams.microcloud.account.Account
import app.microteams.microcloud.account.AccountService
import app.microteams.microcloud.customer.Customer
import app.microteams.microcloud.customer.CustomerService
import app.microteams.microcloud.machine.ai.AiMode
import app.microteams.microcloud.machine.ai.AiStatus
import app.microteams.microcloud.machine.instance.Machine
import app.microteams.microcloud.machine.instance.MachineRepository
import app.microteams.microcloud.machine.instance.MachineService
import app.microteams.microcloud.machine.instance.MachineStatus
import app.microteams.microcloud.machine.offering.OfferingService
import app.microteams.microcloud.model.ClaimWarmMachineRequestDTO
import app.microteams.microcloud.model.CreateMachineRequestDTO
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.rucca.cheese.common.error.BadRequestError
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class MachineWarmClaimTest
@Autowired
constructor(private val service: MachineService, private val machines: MachineRepository) {
    @MockkBean private lateinit var customers: CustomerService
    @MockkBean private lateinit var accounts: AccountService
    @MockkBean private lateinit var offerings: OfferingService

    @BeforeEach
    fun recipients() {
        every { customers.getCustomer(11, 22) } returns Customer(tenantId = 11)
        every { accounts.getAccount(11, 33) } returns Account(tenantId = 11, customerId = 22)
        every { accounts.getAccount(11, 34) } returns Account(tenantId = 11, customerId = 99)
        every { offerings.getUsableForTenant(11, 1) } returns mockk(relaxed = true)
    }

    private fun machine(warm: Boolean = true, ready: Boolean = true): Long =
        machines
            .saveAndFlush(
                Machine(
                    tenantId = 11,
                    customerId = 1,
                    accountId = 1,
                    hostname = "unused",
                    offeringId = 1,
                    typeId = 1,
                    templateId = 1,
                    placementId = 1,
                    networkId = 1,
                    loginUser = "dev",
                    cores = 1,
                    memoryMb = 512,
                    diskGb = 4,
                    warmPoolKey = if (warm) UUID.randomUUID().toString() else null,
                    aiMode = AiMode.NONE,
                    aiStatus = AiStatus.DISABLED,
                    status = if (ready) MachineStatus.RUNNING else MachineStatus.PROVISIONING,
                )
            )
            .id!!

    private fun request(key: String = "room-a", account: Long = 33) =
        ClaimWarmMachineRequestDTO(claimKey = key, customerId = 22, accountId = account)

    @Test
    fun claimChangesBillingOnceAndRetriesWithoutRestarting() {
        val id = machine()
        val first = service.claimWarmMachine(11, id, request())
        assertEquals(22L, first.customerId)
        assertEquals(33L, first.accountId)
        assertEquals(33L, first.ccproxyAccountId)
        assertEquals(first, service.claimWarmMachine(11, id, request()))
        assertFails { service.claimWarmMachine(11, id, request("room-b")) }
        assertEquals(MachineStatus.RUNNING, machines.findById(id).get().status)
    }

    @Test
    fun claimRejectsOtherTenantsOrdinaryMachinesAndUnreadyMachines() {
        assertFails { service.claimWarmMachine(12, machine(), request()) }
        assertFails { service.claimWarmMachine(11, machine(warm = false), request()) }
        assertFails { service.claimWarmMachine(11, machine(ready = false), request()) }
    }

    @Test
    fun receivingAccountMustBelongToReceivingCustomer() {
        val id = machine()
        assertFails { service.claimWarmMachine(11, id, request(account = 34)) }
        assertEquals(1L, machines.findById(id).get().customerId)
    }

    @Test
    fun deletedWarmCreationKeyReturnsAClientErrorWithoutRecreating() {
        val id = machine()
        val deleted = machines.findById(id).get()
        val key = deleted.warmPoolKey!!
        deleted.deletedAt = LocalDateTime.now()
        machines.saveAndFlush(deleted)
        val request =
            CreateMachineRequestDTO(
                customerId = 1,
                accountId = 1,
                hostname = "unused",
                offeringId = 1,
                cores = 1,
                memoryMb = 512,
                diskGb = 4,
                user = "dev",
                warmPoolKey = key,
                aiMode = "none",
            )
        val error = assertFailsWith<BadRequestError> { service.createMachine(11, request) }
        assertEquals("warmPoolKey belongs to a deleted machine; use a new key", error.message)
        assertEquals(id, machines.findByTenantIdAndWarmPoolKey(11, key)?.id)
        assertEquals(null, machines.findByTenantIdAndWarmPoolKey(12, key))
    }

    @Test
    fun competingRoomsCannotBothClaim() {
        val id = machine()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                executor
                    .invokeAll(
                        listOf("room-a", "room-b").map { key ->
                            Callable {
                                runCatching { service.claimWarmMachine(11, id, request(key)) }
                                    .isSuccess
                            }
                        }
                    )
                    .map { it.get() }
            assertEquals(1, results.count { it })
        } finally {
            executor.shutdownNow()
        }
    }
}
