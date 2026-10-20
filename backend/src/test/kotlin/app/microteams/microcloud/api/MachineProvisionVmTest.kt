/*
 *  Description: Integration test for provisioning a VM-kind machine. The substrate and tenant flow
 *               are identical to the LXC path (offering / customer / account / IP lease); what
 *               differs is the provisioner branch. With the template's kind = VM and a baked
 *               template vmid recorded on the upload, creating a machine drives the Proxmox VM path
 *               (qm clone → cloud-init config → firewall → start), NOT the LXC path (pct create). Proxmox and
 *               the operator SSH are mocked, so the async worker runs without a real cluster.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.api

import app.microteams.microcloud.machine.MachineKind
import app.microteams.microcloud.machine.proxmox.GuestFirewallRule
import app.microteams.microcloud.machine.proxmox.OperatorSsh
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.template.MachineTemplate
import app.microteams.microcloud.machine.template.MachineTemplateRepository
import app.microteams.microcloud.machine.template.TemplateUpload
import app.microteams.microcloud.machine.template.TemplateUploadRepository
import app.microteams.microcloud.machine.template.TemplateUploadStatus
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
                            """{"name":"standard","placementIds":[$placementId],"coresMin":1,"coresMax":4,"memoryMbMin":1024,"memoryMbMax":8192,"diskGbMin":10,"diskGbMax":100,"dataDiskGb":32}""",
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

        every { proxmoxClient.resizeVmDisk(any(), any(), any(), any(), any()) } returns
            "UPID:pve:resize-task"
        // A linked clone's root disk is a thin child of the template's base volume.
        every { proxmoxClient.vmConfigValue(any(), any(), any(), "scsi0") } returns
            "local-lvm:base-9000-disk-0/vm-1-disk-0,discard=on,size=3G"

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
                .andExpect(jsonPath("$.dataDiskGb").value(32))
                .andExpect(jsonPath("$.placement.kind").value("proxmox/vm"))
                .andExpect(jsonPath("$.placement.cluster").value("lab"))
                .andExpect(jsonPath("$.placement.node").value("pve"))
                .andReturn()
        val machineId = JSONObject(res.response.contentAsString).getLong("id")

        // The async worker takes the VM branch: it clones the baked template and never touches the
        // LXC path.
        val cloneParams = slot<Map<String, String>>()
        verify(timeout = 5000) {
            proxmoxClient.cloneVm(any(), eq("pve"), eq(9000), capture(cloneParams))
        }
        verify(exactly = 0) { proxmoxClient.createLxc(any(), any(), any()) }
        // A linked clone (~3 s), not a full copy of the template's disk (20-24 s on pve119).
        assertEquals("0", cloneParams.captured["full"])

        // cloud-init does not upgrade packages on first boot (77 s on pve119); each disk gets its
        // own I/O thread, the root disk keeps its options, and the type's data disk is attached.
        val config = slot<Map<String, String>>()
        verify(timeout = 5000) {
            proxmoxClient.setVmConfig(any(), eq("pve"), any(), capture(config))
        }
        assertEquals("0", config.captured["ciupgrade"])
        assertEquals(
            "local-lvm:base-9000-disk-0/vm-1-disk-0,size=3G,discard=on,iothread=1",
            config.captured["scsi0"],
        )
        assertEquals("local-lvm:32,discard=on,iothread=1", config.captured["scsi1"])
        // The balloon device stays (free-page reporting rides on it).
        assertFalse("balloon" in config.captured)

        // The disk resize is a Proxmox task that holds the VM's config lock until the volume has
        // grown, and qm start gives up on that lock after 10 s. So the start must come only after
        // the resize task was awaited — a start fired straight after the resize call failed with
        // "can't lock file ... got timeout" on a slow thin pool (pve119, VM 147, 2026-09-03).
        verify(timeout = 5000) { proxmoxClient.startVm(any(), eq("pve"), any()) }

        // The VM is put behind its firewall before it first boots, holding its own leased address,
        // and kept out of this network's range like every other guest's.
        val isolation = slot<List<GuestFirewallRule>>()
        verifyOrder {
            proxmoxClient.isolateGuest(
                any(),
                eq("pve"),
                any(),
                eq(true),
                eq("10.8.0.10"),
                capture(isolation),
            )
            proxmoxClient.startVm(any(), eq("pve"), any())
        }
        assertTrue(
            GuestFirewallRule("in", "DROP", source = "10.8.0.10-10.8.0.20") in isolation.captured
        )
        assertTrue(GuestFirewallRule("out", "DROP", dest = "10.0.0.0/8") in isolation.captured)

        verifyOrder {
            proxmoxClient.resizeVmDisk(any(), eq("pve"), any(), eq("scsi0"), any())
            proxmoxClient.waitForTask(any(), eq("UPID:pve:resize-task"), any())
            proxmoxClient.startVm(any(), eq("pve"), any())
        }

        // Wait until provisioning finished (the machine has a vmid to act on), then delete it: the
        // VM branch must qm-stop the (not "stopped") VM before qm-destroying it, since qm destroy
        // refuses a running VM — and never take the LXC destroy path.
        waitForStatus(machineId, "running", secret)
        mockMvc
            .perform(get("/machine/$machineId").header("Authorization", "Bearer $secret"))
            .andExpect(jsonPath("$.placement.kind").value("proxmox/vm"))
            .andExpect(jsonPath("$.dataDiskGb").value(32))
        mockMvc
            .perform(post("/machine/$machineId/suspend").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
        waitForStatus(machineId, "suspended", secret)
        verify(exactly = 1) { proxmoxClient.suspendVm(any(), eq("pve"), any()) }
        mockMvc
            .perform(post("/machine/$machineId/resume").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
        waitForStatus(machineId, "running", secret)
        verify(exactly = 1) { proxmoxClient.resumeVm(any(), eq("pve"), any()) }
        verify(exactly = 1) { proxmoxClient.cloneVm(any(), any(), any(), any()) }
        every { proxmoxClient.suspendVm(any(), any(), any()) } throws
            IllegalStateException("checkpoint storage unavailable")
        mockMvc
            .perform(post("/machine/$machineId/suspend").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
        waitForStatus(machineId, "error", secret)
        verify(exactly = 0) { proxmoxClient.shutdownVm(any(), any(), any()) }
        verify(exactly = 0) { proxmoxClient.stopVm(any(), any(), any()) }
        verify(exactly = 0) { proxmoxClient.destroyVm(any(), any(), any()) }
        mockMvc
            .perform(delete("/machine/$machineId").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
        verify(timeout = 5000) { proxmoxClient.destroyVm(any(), eq("pve"), any()) }
        verifyOrder {
            proxmoxClient.stopVm(any(), eq("pve"), any())
            proxmoxClient.destroyVm(any(), eq("pve"), any())
        }
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
