/*
 *  Description: Per-stream billing accounts. A machine carries three fund accounts — compute /
 *               newapi / ccproxy — so a tenant can bill each cost stream separately. Omitted AI
 *               accounts default to the compute account; a provided account must belong to the
 *               tenant. Proxmox is mocked; SSH init disabled.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.api

import app.microteams.microcloud.machine.MachineKind
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.template.MachineTemplate
import app.microteams.microcloud.machine.template.MachineTemplateRepository
import app.microteams.microcloud.machine.template.TemplateUpload
import app.microteams.microcloud.machine.template.TemplateUploadRepository
import app.microteams.microcloud.machine.template.TemplateUploadStatus
import com.ninjasquad.springmockk.MockkBean
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
@TestPropertySource(properties = ["microcloud.provisioning.init-command="])
class MachineAccountsTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val templateRepository: MachineTemplateRepository,
    private val uploadRepository: TemplateUploadRepository,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    @MockkBean(relaxed = true) private lateinit var proxmoxClient: ProxmoxClient

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
    fun threeBillingAccountsDefaultAndSplit() {
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
                        name = "deb-acct-${System.nanoTime()}",
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
                    """{"placementId":$placementId,"startIp":"10.6.0.10","endIp":"10.6.0.20","gateway":"10.6.0.1","prefixLength":24,"bridge":"vmbr0"}""",
                )
                .response
                .contentAsString
        )
        val typeId =
            JSONObject(
                    post(
                            "/machine/type",
                            admin,
                            """{"name":"standard","placementIds":[$placementId],"coresMin":1,"coresMax":4,"memoryMbMin":512,"memoryMbMax":8192,"diskGbMin":4,"diskGbMax":100}""",
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
            JSONObject(post("/tenant", admin, """{"name":"acct"}""").response.contentAsString)
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
        val acctCompute =
            JSONObject(
                    post("/account", secret, """{"customerId":$customerId,"name":"compute"}""")
                        .response
                        .contentAsString
                )
                .getLong("id")
        val acctAi =
            JSONObject(
                    post("/account", secret, """{"customerId":$customerId,"name":"ai"}""")
                        .response
                        .contentAsString
                )
                .getLong("id")

        fun body(extra: String) =
            """{"customerId":$customerId,"accountId":$acctCompute,"hostname":"h","offeringId":$offeringId,"cores":1,"memoryMb":512,"diskGb":4,"user":"dev","sshPubkey":"ssh-ed25519 AAAA dev"$extra}"""

        // 1) Omit the AI accounts → both default to the compute account.
        mockMvc
            .perform(
                post("/machine")
                    .header("Authorization", "Bearer $secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(""))
            )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.accountId").value(acctCompute))
            .andExpect(jsonPath("$.newapiAccountId").value(acctCompute))
            .andExpect(jsonPath("$.ccproxyAccountId").value(acctCompute))

        // 2) Split: newapi billed to a different (owned) account; ccproxy still defaults to
        // compute.
        mockMvc
            .perform(
                post("/machine")
                    .header("Authorization", "Bearer $secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(""","newapiAccountId":$acctAi"""))
            )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.newapiAccountId").value(acctAi))
            .andExpect(jsonPath("$.ccproxyAccountId").value(acctCompute))

        // 3) An account that isn't the tenant's is rejected (404 on lookup).
        mockMvc
            .perform(
                post("/machine")
                    .header("Authorization", "Bearer $secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(""","ccproxyAccountId":99999999"""))
            )
            .andExpect(status().isNotFound)
    }
}
