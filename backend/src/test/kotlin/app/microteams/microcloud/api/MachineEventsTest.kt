/*
 *  Description: Integration test for the machine event log (GET /machine/{id}/events). A provision
 *               leaves the PROVISION sequence ending in RUNNING; a provision whose Proxmox task fails
 *               keeps the events written before the failure plus a FAILED one, and they are readable
 *               WHILE the provisioner's transaction is still open (the recorder commits on its own);
 *               a ccproxy login is recorded from LOGIN_STARTED through what the poller saw to
 *               LOGIN_READY; a deleted machine's events stay readable by its tenant and the
 *               super-admin, and never by another tenant. Proxmox and ccproxy are mocked; SSH init is
 *               disabled.
 *
 *  Author(s):
 *      Zhifei Li    <andylizf@outlook.com>
 *
 */

package app.microteams.microcloud.api

import app.microteams.microcloud.machine.MachineKind
import app.microteams.microcloud.machine.ai.CcproxyClient
import app.microteams.microcloud.machine.ai.CcproxyLoginRequest
import app.microteams.microcloud.machine.ai.CcproxyMachine
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.template.MachineTemplate
import app.microteams.microcloud.machine.template.MachineTemplateRepository
import app.microteams.microcloud.machine.template.TemplateUpload
import app.microteams.microcloud.machine.template.TemplateUploadRepository
import app.microteams.microcloud.machine.template.TemplateUploadStatus
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.rucca.cheese.common.error.BadRequestError
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureMockMvc
@TestMethodOrder(OrderAnnotation::class)
@TestPropertySource(properties = ["microcloud.provisioning.init-command="])
class MachineEventsTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val templateRepository: MachineTemplateRepository,
    private val uploadRepository: TemplateUploadRepository,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    @MockkBean(relaxed = true) private lateinit var proxmoxClient: ProxmoxClient
    @MockkBean private lateinit var ccproxyClient: CcproxyClient

    private lateinit var adminToken: String
    private lateinit var secret: String
    private lateinit var otherSecret: String
    private var customerId: Long = -1
    private var accountId: Long = -1
    private var offeringId: Long = -1
    private var machineId: Long = -1

    private fun post(url: String, token: String, body: String) =
        mockMvc
            .perform(
                post(url)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
            .andReturn()

    private fun getJson(url: String, token: String): JSONObject =
        JSONObject(
            mockMvc
                .perform(get(url).header("Authorization", "Bearer $token"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        )

    private fun events(id: Long, token: String = secret, query: String = ""): JSONArray =
        getJson("/machine/$id/events$query", token).getJSONArray("items")

    private fun phases(items: JSONArray, action: String): List<String> =
        (0 until items.length())
            .map { items.getJSONObject(it) }
            .filter { it.getString("action") == action }
            .map { it.getString("phase") }

    private fun createMachine(hostname: String, aiMode: String = "none"): Long =
        JSONObject(
                mockMvc
                    .perform(
                        post("/machine")
                            .header("Authorization", "Bearer $secret")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """{"customerId":$customerId,"accountId":$accountId,"hostname":"$hostname","offeringId":$offeringId,"cores":2,"memoryMb":2048,"diskGb":20,"user":"dev","sshPubkey":"ssh-ed25519 AAAA dev","aiMode":"$aiMode"}"""
                            )
                    )
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .contentAsString
            )
            .getLong("id")

    /** Poll until [ready] holds on the machine, or fail after ~[seconds]. */
    private fun waitFor(id: Long, seconds: Int, what: String, ready: (JSONObject) -> Boolean) {
        repeat(seconds * 10) {
            val body =
                mockMvc
                    .perform(get("/machine/$id").header("Authorization", "Bearer $secret"))
                    .andReturn()
                    .response
                    .contentAsString
            if (runCatching { ready(JSONObject(body)) }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        error("machine $id did not reach $what in time")
    }

    private fun waitForStatus(id: Long, status: String) =
        waitFor(id, 10, "status $status") { it.getString("status") == status }

    /** Poll until [ready] holds, or fail after ~[seconds]. */
    private fun waitUntil(seconds: Int, what: String, ready: () -> Boolean) {
        repeat(seconds * 10) {
            if (ready()) return
            Thread.sleep(100)
        }
        error("$what did not happen in time")
    }

    private fun waitForEvent(id: Long, action: String, phase: String) =
        waitUntil(10, "machine $id recording $action/$phase") {
            phases(events(id), action).contains(phase)
        }

    /**
     * ccproxy is not wired unless a test says so: the provisioner asks for every machine, and a
     * strict mock throws when unasked. springmockk clears every stub after each test, so this is
     * per test — and so a test's own stubs need no undoing.
     */
    @BeforeEach
    fun ccproxyIsNotWired() {
        every { ccproxyClient.isConfigured() } returns false
    }

    @BeforeAll
    fun setup() {
        adminToken =
            JSONObject(
                    mockMvc
                        .perform(
                            post("/superadmin/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"password":"$superadminPassword"}""")
                        )
                        .andReturn()
                        .response
                        .contentAsString
                )
                .getString("token")

        val templateId =
            templateRepository
                .save(
                    MachineTemplate(
                        name = "debian13-ev-${System.nanoTime()}",
                        kind = MachineKind.PROXMOX_LXC,
                    )
                )
                .id!!
        val clusterId =
            JSONObject(
                    post(
                            "/machine/proxmox",
                            adminToken,
                            """{"name":"lab","apiUrl":"https://pve:8006","tokenId":"m@pve!t","tokenSecret":"s"}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        val placementId =
            JSONObject(
                    post(
                            "/machine/placement",
                            adminToken,
                            """{"kind":"proxmox/lxc","name":"p1","clusterId":$clusterId,"node":"pve","pool":"microcloud","storage":"local-lvm"}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        post(
            "/machine/network",
            adminToken,
            """{"placementId":$placementId,"startIp":"10.6.0.10","endIp":"10.6.0.30","gateway":"10.6.0.1","prefixLength":24,"bridge":"vmbr0"}""",
        )
        val typeId =
            JSONObject(
                    post(
                            "/machine/type",
                            adminToken,
                            """{"name":"standard","placementIds":[$placementId],"coresMin":1,"coresMax":4,"memoryMbMin":1024,"memoryMbMax":8192,"diskGbMin":10,"diskGbMax":100}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        val zoneId =
            JSONObject(
                    post(
                            "/machine/zone",
                            adminToken,
                            """{"name":"z1","placementIds":[$placementId]}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        uploadRepository.save(
            TemplateUpload(
                templateId = templateId,
                placementId = placementId,
                status = TemplateUploadStatus.DONE,
                volid = "local:vztmpl/x.tar.zst",
            )
        )
        val tenantId =
            JSONObject(post("/tenant", adminToken, """{"name":"evt"}""").response.contentAsString)
                .getLong("id")
        secret =
            JSONObject(post("/tenant/$tenantId/secret", adminToken, "{}").response.contentAsString)
                .getString("secret")
        offeringId =
            JSONObject(
                    post(
                            "/machine/offering",
                            adminToken,
                            """{"tenantId":$tenantId,"machineTypeId":$typeId,"zoneId":$zoneId,"templateId":$templateId}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        customerId =
            JSONObject(
                    post("/customer", secret, """{"externalRef":"u-1"}""").response.contentAsString
                )
                .getLong("id")
        accountId =
            JSONObject(
                    post("/account", secret, """{"customerId":$customerId,"name":"main"}""")
                        .response
                        .contentAsString
                )
                .getLong("id")
        val otherTenantId =
            JSONObject(post("/tenant", adminToken, """{"name":"other"}""").response.contentAsString)
                .getLong("id")
        otherSecret =
            JSONObject(
                    post("/tenant/$otherTenantId/secret", adminToken, "{}").response.contentAsString
                )
                .getString("secret")
    }

    @Test
    @Order(1)
    fun provisionLeavesTheSequenceEndingInRunning() {
        every { proxmoxClient.createLxc(any(), any(), any()) } returns "UPID:pve:00001234:create"
        machineId = createMachine("ev-1")
        waitForStatus(machineId, "running")

        val items = events(machineId)
        assertEquals(
            listOf("STARTED", "PVE_TASK_SUBMITTED", "PVE_TASK_DONE", "RUNNING"),
            phases(items, "PROVISION"),
        )
        for (i in 0 until items.length()) {
            val e = items.getJSONObject(i)
            assertEquals(machineId, e.getLong("machineId"))
            assertEquals("INFO", e.getString("level"))
            assertTrue(e.getString("message").isNotBlank())
            // Ordered by `at` ascending.
            if (i > 0)
                assertTrue(
                    items.getJSONObject(i - 1).getString("at") <= e.getString("at"),
                    "events out of order",
                )
        }
        // The evidence: the task's UPID travels with the submitted event.
        assertTrue(items.getJSONObject(1).getString("detail").contains("UPID:pve:00001234:create"))

        // `since` = the last event's timestamp returns just that event; a small page paginates.
        val last = items.getJSONObject(items.length() - 1)
        assertEquals(1, events(machineId, query = "?since=${last.getString("at")}").length())
        val page = getJson("/machine/$machineId/events?page_size=2", secret)
        assertEquals(2, page.getJSONArray("items").length())
        assertTrue(page.getJSONObject("page").getBoolean("hasMore"))
    }

    @Test
    @Order(2)
    fun failedProxmoxTaskKeepsTheEarlierEventsAndRecordsTheFailure() {
        // The Proxmox task blocks until released, then fails the way machine 604 did. While it
        // blocks, the provisioner's own transaction is still open — so anything readable through
        // the API at that moment was committed by the recorder on its own.
        val gate = CountDownLatch(1)
        every { proxmoxClient.createLxc(any(), any(), any()) } returns "UPID:pve:0000604:create"
        every { proxmoxClient.waitForTask(any(), any(), any()) } answers
            {
                gate.await(10, TimeUnit.SECONDS)
                throw BadRequestError(
                    "Proxmox task UPID:pve:0000604:create failed: can't lock file " +
                        "'/var/lock/qemu-server/lock-604.conf' - got timeout"
                )
            }
        val id = createMachine("ev-2")
        waitForEvent(id, "PROVISION", "PVE_TASK_SUBMITTED")
        assertEquals("provisioning", getJson("/machine/$id", secret).getString("status"))
        assertEquals(listOf("STARTED", "PVE_TASK_SUBMITTED"), phases(events(id), "PROVISION"))

        gate.countDown()
        waitForStatus(id, "error")
        val items = events(id)
        assertEquals(listOf("STARTED", "PVE_TASK_SUBMITTED", "FAILED"), phases(items, "PROVISION"))
        val failed = items.getJSONObject(items.length() - 1)
        assertEquals("ERROR", failed.getString("level"))
        assertTrue(failed.getString("message").contains("got timeout"))
        assertTrue(failed.getString("detail").contains("BadRequestError"))
    }

    @Test
    @Order(3)
    fun ccproxyLoginIsRecordedFromStartToReady() {
        // ccproxy: registered at birth (id 9); the login opens request 77; the poller then sees
        // loggingIn once, and ready with a credential on its next look.
        val polls = AtomicInteger()
        val registered = CcproxyMachine(9, "awaitingLogin", false, null, null, null)
        every { ccproxyClient.isConfigured() } returns true
        every { ccproxyClient.getSshPubkey() } returns "ssh-ed25519 CC ccproxy"
        every { ccproxyClient.createMachine(any(), any(), any(), any()) } returns registered
        every { ccproxyClient.getMachine(9) } answers
            {
                when {
                    polls.get() == 0 -> registered
                    polls.get() == 1 ->
                        registered.copy(status = "loggingIn", currentLoginRequestId = 77)
                    else -> registered.copy(status = "ready", hasCredential = true)
                }
            }
        every { ccproxyClient.startLogin(9) } answers
            {
                polls.set(1)
                CcproxyLoginRequest(77, "preparing", "acct@example.com")
            }
        val id = createMachine("ev-3", aiMode = "ccproxy")
        waitForStatus(id, "running")
        waitForEvent(id, "PROVISION", "CCPROXY_REGISTERED")
        // The first poll sees loggingIn; the poller sleeps 5 s between looks, then sees ready.
        waitForEvent(id, "AI_LOGIN", "LOGIN_POLLED")
        polls.set(2)
        waitFor(id, 15, "aiStatus ready") { it.getString("aiStatus") == "ready" }

        val items = events(id)
        assertEquals(
            listOf("LOGIN_STARTED", "LOGIN_POLLED", "LOGIN_POLLED", "LOGIN_READY"),
            phases(items, "AI_LOGIN"),
        )
        val started =
            (0 until items.length())
                .map { items.getJSONObject(it) }
                .first { it.getString("phase") == "LOGIN_STARTED" }
        assertTrue(started.getString("detail").contains("loginRequestId=77"))
        assertTrue(started.getString("detail").contains("acct@example.com"))
        val polled =
            (0 until items.length())
                .map { items.getJSONObject(it) }
                .filter { it.getString("phase") == "LOGIN_POLLED" }
        assertTrue(polled[0].getString("detail").contains("status=loggingIn"))
        assertTrue(polled[1].getString("detail").contains("status=ready"))
    }

    @Test
    @Order(4)
    fun deletedMachineKeepsItsEventsReadable() {
        mockMvc
            .perform(delete("/machine/$machineId").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
        waitUntil(10, "machine $machineId disappearing") {
            mockMvc
                .perform(get("/machine/$machineId").header("Authorization", "Bearer $secret"))
                .andReturn()
                .response
                .status == 404
        }

        // The machine is gone from every machine read, its history is not.
        val items = events(machineId)
        assertEquals(
            listOf("STARTED", "PVE_TASK_SUBMITTED", "PVE_TASK_DONE", "RUNNING"),
            phases(items, "PROVISION"),
        )
        assertEquals(listOf("PVE_TASK_SUBMITTED", "PVE_TASK_DONE", "DONE"), phases(items, "DELETE"))
        // The super-admin reads it too.
        assertEquals(items.length(), events(machineId, token = adminToken).length())
    }

    @Test
    @Order(5)
    fun anotherTenantCannotReadThem() {
        mockMvc
            .perform(
                get("/machine/$machineId/events").header("Authorization", "Bearer $otherSecret")
            )
            .andExpect(status().isForbidden)
        mockMvc
            .perform(get("/machine/999999/events").header("Authorization", "Bearer $secret"))
            .andExpect(status().isNotFound)
    }
}
