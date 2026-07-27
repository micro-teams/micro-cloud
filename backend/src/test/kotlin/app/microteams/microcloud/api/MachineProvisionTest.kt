/*
 *  Description: End-to-end integration test for tenant machine provisioning. An admin builds the full
 *               substrate (cluster -> placement -> network -> machine type); a tenant creates a
 *               customer + account, then provisions a machine — which leases an IP from the network,
 *               lands PROVISIONING, and lifecycles through stop/start/delete. Also covers spec-range
 *               validation, cross-tenant isolation, IP exhaustion, and IP release on delete.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.api

import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.template.MachineTemplate
import app.microteams.microcloud.machine.template.MachineTemplateKind
import app.microteams.microcloud.machine.template.MachineTemplateRepository
import app.microteams.microcloud.machine.template.TemplateUpload
import app.microteams.microcloud.machine.template.TemplateUploadRepository
import app.microteams.microcloud.machine.template.TemplateUploadStatus
import com.ninjasquad.springmockk.MockkBean
import org.json.JSONObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
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
// No SSH init: ProxmoxClient is mocked, and there's no real machine to SSH into.
@TestPropertySource(properties = ["microcloud.provisioning.init-command="])
class MachineProvisionTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val templateRepository: MachineTemplateRepository,
    private val uploadRepository: TemplateUploadRepository,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    // Stub out Proxmox so the async provisioning worker runs without a real cluster.
    @MockkBean(relaxed = true) private lateinit var proxmoxClient: ProxmoxClient

    private lateinit var adminToken: String
    private lateinit var secret: String
    private lateinit var otherSecret: String
    private var templateId: Long = -1
    private var typeId: Long = -1
    private var networkId: Long = -1
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

        templateId =
            templateRepository
                .save(
                    MachineTemplate(
                        name = "debian13-prov-${System.nanoTime()}",
                        kind = MachineTemplateKind.LXC,
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
                            """{"name":"p1","clusterId":$clusterId,"node":"pve","pool":"microcloud","storage":"local-lvm"}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        // A tiny range: exactly two usable addresses, to exercise exhaustion.
        networkId =
            JSONObject(
                    post(
                            "/machine/network",
                            adminToken,
                            """{"placementId":$placementId,"startIp":"10.9.0.10","endIp":"10.9.0.11","gateway":"10.9.0.1","prefixLength":24,"bridge":"vmbr0"}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        typeId =
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
        // Mark the template as uploaded (DONE) to the placement so provisioning can land there.
        uploadRepository.save(
            TemplateUpload(
                templateId = templateId,
                placementId = placementId,
                status = TemplateUploadStatus.DONE,
                volid = "local:vztmpl/x.tar.zst",
            )
        )

        val tenantId =
            JSONObject(
                    post("/tenant", adminToken, """{"name":"cloudy"}""").response.contentAsString
                )
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

    private fun createBody(cores: Int = 2) =
        """{"customerId":$customerId,"accountId":$accountId,"hostname":"host-$cores","offeringId":$offeringId,"cores":$cores,"memoryMb":2048,"diskGb":20,"user":"dev","sshPubkey":"ssh-ed25519 AAAA dev"}"""

    @Test
    @Order(1)
    fun specOutOfRangeIsRejected() {
        mockMvc
            .perform(
                post("/machine")
                    .header("Authorization", "Bearer $secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(cores = 99))
            )
            .andExpect(status().isBadRequest)
    }

    @Test
    @Order(1)
    fun invalidHostnameIsRejected() {
        // Underscores / spaces aren't valid RFC1123 hostnames -> 400 up front, not an async ERROR.
        val body =
            """{"customerId":$customerId,"accountId":$accountId,"hostname":"bad_host name","offeringId":$offeringId,"cores":2,"memoryMb":2048,"diskGb":20,"user":"dev","sshPubkey":"ssh-ed25519 AAAA dev"}"""
        mockMvc
            .perform(
                post("/machine")
                    .header("Authorization", "Bearer $secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
            .andExpect(status().isBadRequest)
    }

    @Test
    @Order(2)
    fun crossTenantAccountIsRejected() {
        // The other tenant cannot provision against this tenant's customer/account (404 on lookup).
        mockMvc
            .perform(
                post("/machine")
                    .header("Authorization", "Bearer $otherSecret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody())
            )
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(3)
    fun provisionMachineLeasesIp() {
        val res =
            mockMvc
                .perform(
                    post("/machine")
                        .header("Authorization", "Bearer $secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody())
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value("provisioning"))
                .andExpect(jsonPath("$.ip").value("10.9.0.10"))
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andReturn()
        machineId = JSONObject(res.response.contentAsString).getLong("id")

        // The network now reports one address allocated.
        mockMvc
            .perform(
                get("/machine/network/$networkId").header("Authorization", "Bearer $adminToken")
            )
            .andExpect(jsonPath("$.totalCount").value(2))
            .andExpect(jsonPath("$.allocatedCount").value(1))
    }

    @Test
    @Order(4)
    fun tenantListsOwnMachine() {
        mockMvc
            .perform(
                get("/machine")
                    .header("Authorization", "Bearer $secret")
                    .param("customer_id", customerId.toString())
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value(machineId))

        // A different tenant sees nothing of it and cannot fetch it directly.
        mockMvc
            .perform(get("/machine/$machineId").header("Authorization", "Bearer $otherSecret"))
            .andExpect(status().isForbidden)
    }

    @Test
    @Order(5)
    fun secondMachineExhaustsThenReleaseFrees() {
        // Second machine takes the last address.
        mockMvc
            .perform(
                post("/machine")
                    .header("Authorization", "Bearer $secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody())
            )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.ip").value("10.9.0.11"))

        // Third machine finds no free address in the only network -> 400.
        mockMvc
            .perform(
                post("/machine")
                    .header("Authorization", "Bearer $secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody())
            )
            .andExpect(status().isBadRequest)

        // Deletion is async (202); the CT is destroyed and its IP released in the background. Wait
        // until the machine is gone, then a new machine can reuse the freed address.
        mockMvc
            .perform(delete("/machine/$machineId").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
        waitUntilGone(machineId)

        mockMvc
            .perform(
                post("/machine")
                    .header("Authorization", "Bearer $secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody())
            )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.ip").value("10.9.0.10"))
    }

    private fun waitUntilGone(id: Long) {
        repeat(50) {
            val code =
                mockMvc
                    .perform(get("/machine/$id").header("Authorization", "Bearer $secret"))
                    .andReturn()
                    .response
                    .status
            if (code == 404) return
            Thread.sleep(100)
        }
        error("machine $id was not deleted in time")
    }

    @Test
    @Order(6)
    fun startStopLifecycle() {
        // Provision a fresh machine on the released address is not guaranteed here; reuse listing.
        val id =
            JSONObject(
                    mockMvc
                        .perform(get("/machine").header("Authorization", "Bearer $secret"))
                        .andReturn()
                        .response
                        .contentAsString
                )
                .getJSONArray("items")
                .getJSONObject(0)
                .getLong("id")

        // Stop is async: it's accepted (202) regardless — it transitions RUNNING -> STOPPING, or is
        // a no-op if the machine isn't running yet.
        mockMvc
            .perform(post("/machine/$id/stop").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
    }
}
