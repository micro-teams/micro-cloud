/*
 *  Description: Integration test for the NEWAPI ai mode. With the platform default mode = NEWAPI and
 *               newapi enabled, provisioning a machine mints a per-machine newapi token and reports
 *               aiMode=newapi / aiStatus=ready — orthogonally to the machine's own RUNNING status.
 *               Proxmox and the newapi client are mocked; SSH init is disabled.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.api

import app.microteams.microcloud.machine.MachineKind
import app.microteams.microcloud.machine.ai.NewapiClient
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.template.MachineTemplate
import app.microteams.microcloud.machine.template.MachineTemplateRepository
import app.microteams.microcloud.machine.template.TemplateUpload
import app.microteams.microcloud.machine.template.TemplateUploadRepository
import app.microteams.microcloud.machine.template.TemplateUploadStatus
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
@TestPropertySource(
    properties =
        [
            "microcloud.provisioning.init-command=",
            "microcloud.newapi.root-password=test-root-pw",
            "microcloud.newapi.machine-base-url=http://host:8080/newapi",
        ]
)
class MachineAiNewapiTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val templateRepository: MachineTemplateRepository,
    private val uploadRepository: TemplateUploadRepository,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    @MockkBean(relaxed = true) private lateinit var proxmoxClient: ProxmoxClient
    @MockkBean private lateinit var newapiClient: NewapiClient

    private fun post(url: String, token: String, body: String) =
        mockMvc
            .perform(
                post(url)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
            .andReturn()

    @Test
    fun newapiMachineMintsATokenAndReportsAiReady() {
        every { newapiClient.isConfigured() } returns true
        every { newapiClient.ensureToken(any(), any()) } returns 77
        every { newapiClient.revealKey(77) } returns "sk-relaytoken"

        val admin =
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
                        name = "debian13-ai-${System.nanoTime()}",
                        kind = MachineKind.PROXMOX_LXC,
                    )
                )
                .id!!
        val clusterId =
            JSONObject(
                    post(
                            "/machine/proxmox",
                            admin,
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
                            admin,
                            """{"kind":"proxmox/lxc","name":"p1","clusterId":$clusterId,"node":"pve","pool":"microcloud","storage":"local-lvm"}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        JSONObject(
            post(
                    "/machine/network",
                    admin,
                    """{"placementId":$placementId,"startIp":"10.7.0.10","endIp":"10.7.0.20","gateway":"10.7.0.1","prefixLength":24,"bridge":"vmbr0"}""",
                )
                .response
                .contentAsString
        )
        val typeId =
            JSONObject(
                    post(
                            "/machine/type",
                            admin,
                            """{"name":"standard","placementIds":[$placementId],"coresMin":1,"coresMax":4,"memoryMbMin":1024,"memoryMbMax":8192,"diskGbMin":10,"diskGbMax":100}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        val zoneId =
            JSONObject(
                    post("/machine/zone", admin, """{"name":"z1","placementIds":[$placementId]}""")
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
            JSONObject(post("/tenant", admin, """{"name":"ait"}""").response.contentAsString)
                .getLong("id")
        val secret =
            JSONObject(post("/tenant/$tenantId/secret", admin, "{}").response.contentAsString)
                .getString("secret")
        val offeringId =
            JSONObject(
                    post(
                            "/machine/offering",
                            admin,
                            """{"tenantId":$tenantId,"machineTypeId":$typeId,"zoneId":$zoneId,"templateId":$templateId}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        val customerId =
            JSONObject(
                    post("/customer", secret, """{"externalRef":"u"}""").response.contentAsString
                )
                .getLong("id")
        val accountId =
            JSONObject(
                    post("/account", secret, """{"customerId":$customerId,"name":"main"}""")
                        .response
                        .contentAsString
                )
                .getLong("id")

        // Create a machine — aiMode defaults to NEWAPI (platform default).
        val res =
            mockMvc
                .perform(
                    post("/machine")
                        .header("Authorization", "Bearer $secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"customerId":$customerId,"accountId":$accountId,"hostname":"ai-1","offeringId":$offeringId,"cores":2,"memoryMb":2048,"diskGb":20,"user":"dev","sshPubkey":"ssh-ed25519 AAAA dev"}"""
                        )
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.aiMode").value("newapi"))
                .andReturn()
        val id = JSONObject(res.response.contentAsString).getLong("id")

        // The async worker mints a newapi token for the machine and lands aiStatus=ready.
        verify(timeout = 5000) { newapiClient.ensureToken("mc-machine-$id", any()) }
        verify(timeout = 5000) { newapiClient.revealKey(77) }
        var aiStatus = ""
        repeat(50) {
            aiStatus =
                JSONObject(
                        mockMvc
                            .perform(get("/machine/$id").header("Authorization", "Bearer $secret"))
                            .andReturn()
                            .response
                            .contentAsString
                    )
                    .getString("aiStatus")
            if (aiStatus == "ready") return@repeat
            Thread.sleep(100)
        }
        org.junit.jupiter.api.Assertions.assertEquals("ready", aiStatus)
    }
}
