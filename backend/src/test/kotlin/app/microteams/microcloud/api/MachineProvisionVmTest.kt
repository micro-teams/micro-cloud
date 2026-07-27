/*
 *  Description: Integration test for provisioning a VM-kind machine. The substrate and tenant flow
 *               are identical to the LXC path (offering / customer / account / IP lease); what
 *               differs is the provisioner branch. With the template's kind = VM and a baked
 *               template vmid recorded on the upload, creating a machine drives the Proxmox VM path
 *               (qm clone → cloud-init config → start), NOT the LXC path (pct create). Proxmox and
 *               the operator SSH are mocked, so the async worker runs without a real cluster.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.api

import app.microteams.microcloud.machine.MachineKind
import app.microteams.microcloud.machine.proxmox.OperatorSsh
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.template.MachineTemplate
import app.microteams.microcloud.machine.template.MachineTemplateRepository
import app.microteams.microcloud.machine.template.TemplateUpload
import app.microteams.microcloud.machine.template.TemplateUploadRepository
import app.microteams.microcloud.machine.template.TemplateUploadStatus
import com.ninjasquad.springmockk.MockkBean
import io.mockk.verify
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureMockMvc
class MachineProvisionVmTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val templateRepository: MachineTemplateRepository,
    private val uploadRepository: TemplateUploadRepository,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    // Stub Proxmox + operator SSH so the async VM provisioning worker runs end-to-end (qm clone →
    // config → start → wait-for-ssh) without a real cluster or a reachable guest.
    @MockkBean(relaxed = true) private lateinit var proxmoxClient: ProxmoxClient
    @MockkBean(relaxed = true) private lateinit var operatorSsh: OperatorSsh

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
    fun vmMachineTakesTheVmProvisioningPath() {
        val adminToken =
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
                        name = "debian13-vm-${System.nanoTime()}",
                        kind = MachineKind.PROXMOX_VM,
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
                            """{"kind":"proxmox/vm","name":"p1","clusterId":$clusterId,"node":"pve","pool":"microcloud","storage":"local-lvm"}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        val networkId =
            JSONObject(
                    post(
                            "/machine/network",
                            adminToken,
                            """{"placementId":$placementId,"startIp":"10.8.0.10","endIp":"10.8.0.20","gateway":"10.8.0.1","prefixLength":24,"bridge":"vmbr0"}""",
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
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
        // The VM template is baked (DONE) on the placement: its upload carries a template vmid, not
        // a vztmpl volid.
        uploadRepository.save(
            TemplateUpload(
                templateId = templateId,
                placementId = placementId,
                status = TemplateUploadStatus.DONE,
                templateVmid = 9000,
            )
        )

        val tenantId =
            JSONObject(post("/tenant", adminToken, """{"name":"vmt"}""").response.contentAsString)
                .getLong("id")
        val secret =
            JSONObject(post("/tenant/$tenantId/secret", adminToken, "{}").response.contentAsString)
                .getString("secret")
        val offeringId =
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
        val customerId =
            JSONObject(
                    post("/customer", secret, """{"externalRef":"u-1"}""").response.contentAsString
                )
                .getLong("id")
        val accountId =
            JSONObject(
                    post("/account", secret, """{"customerId":$customerId,"name":"main"}""")
                        .response
                        .contentAsString
                )
                .getLong("id")

        val res =
            mockMvc
                .perform(
                    post("/machine")
                        .header("Authorization", "Bearer $secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"customerId":$customerId,"accountId":$accountId,"hostname":"vm-1","offeringId":$offeringId,"cores":2,"memoryMb":2048,"diskGb":20,"user":"dev","sshPubkey":"ssh-ed25519 AAAA dev"}"""
                        )
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value("provisioning"))
                .andExpect(jsonPath("$.ip").value("10.8.0.10"))
                .andReturn()
        val machineId = JSONObject(res.response.contentAsString).getLong("id")

        // The async worker takes the VM branch: it clones the baked template and never touches the
        // LXC path.
        verify(timeout = 5000) { proxmoxClient.cloneVm(any(), eq("pve"), eq(9000), any()) }
        verify(exactly = 0) { proxmoxClient.createLxc(any(), any(), any()) }

        // Wait until provisioning finished (the machine has a vmid to act on), then delete it: the
        // VM branch must go through destroyVmGracefully (which stops a running VM before
        // destroying,
        // since qm destroy refuses a running VM) — never the LXC destroy path.
        waitForStatus(machineId, "running", secret)
        mockMvc
            .perform(delete("/machine/$machineId").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
        verify(timeout = 5000) { proxmoxClient.destroyVmGracefully(any(), eq("pve"), any(), any()) }
        verify(exactly = 0) { proxmoxClient.destroyLxc(any(), any(), any()) }
    }

    private fun waitForStatus(id: Long, status: String, secret: String) {
        repeat(50) {
            val body =
                mockMvc
                    .perform(get("/machine/$id").header("Authorization", "Bearer $secret"))
                    .andReturn()
                    .response
                    .contentAsString
            if (runCatching { JSONObject(body).getString("status") }.getOrNull() == status) return
            Thread.sleep(100)
        }
        error("machine $id did not reach $status in time")
    }
}
