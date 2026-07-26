/*
 *  Description: Live end-to-end provisioning test against a REAL Proxmox cluster. Disabled unless the
 *               MICROCLOUD_PVE_URL environment variable is set, so it never runs in CI or a normal
 *               `mvn test`. When enabled it drives the whole stack: register the cluster + placement +
 *               network + machine type + template, have a tenant provision a machine, wait for the
 *               async worker to actually create+start the LXC on Proxmox (status -> running), then
 *               stop / start / destroy it — verifying each transition and cleaning the CT up.
 *
 *               Provide these env vars (the token is used only at runtime, never committed):
 *                 MICROCLOUD_PVE_URL          e.g. https://119pve.ghg.org.cn:8006
 *                 MICROCLOUD_PVE_TOKEN_ID     e.g. microcloud-test@pve!microcloud-test
 *                 MICROCLOUD_PVE_TOKEN_SECRET the token secret
 *                 MICROCLOUD_PVE_NODE         e.g. pve119
 *                 MICROCLOUD_PVE_POOL         e.g. microcloud-test
 *                 MICROCLOUD_PVE_STORAGE      rootfs storage, e.g. local-lvm
 *                 MICROCLOUD_PVE_BRIDGE       e.g. vmbr0
 *                 MICROCLOUD_PVE_OSTEMPLATE   e.g. local:vztmpl/debian-13-standard_13.1-2_amd64.tar.zst
 *                 MICROCLOUD_PVE_IP_START     e.g. 192.168.18.210
 *                 MICROCLOUD_PVE_IP_END       e.g. 192.168.18.211
 *                 MICROCLOUD_PVE_GATEWAY      e.g. 192.168.16.2
 *                 MICROCLOUD_PVE_PREFIX       e.g. 20
 *               Optional (to also run init-machine over SSH from this host on the private net):
 *                 MICROCLOUD_PVE_SSH_PUBKEY   root public key injected into the CT
 *                 MICROCLOUD_PVE_SSH_KEY_PATH matching private key path on this host
 *                 MICROCLOUD_PVE_INIT_CMD     remote command; {user}{sshPubkey}{ip}{gateway} substituted
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.api

import app.microteams.microcloud.machine.template.MachineTemplate
import app.microteams.microcloud.machine.template.MachineTemplateKind
import app.microteams.microcloud.machine.template.MachineTemplateRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MICROCLOUD_PVE_URL", matches = ".+")
class ProxmoxLiveProvisionTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val templateRepository: MachineTemplateRepository,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    private lateinit var adminToken: String
    private lateinit var secret: String
    private var machineId: Long = -1
    private var clusterId: Long = -1
    private var templateId: Long = -1

    private fun env(name: String, default: String? = null): String =
        System.getenv(name) ?: default ?: error("env $name is required for the live provision test")

    /** Turn real provisioning on for this context only, and feed the fallback ostemplate + SSH. */
    @Suppress("unused")
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun provisioningProps(registry: DynamicPropertyRegistry) {
            registry.add("microcloud.provisioning.os-template") {
                System.getenv("MICROCLOUD_PVE_OSTEMPLATE") ?: ""
            }
            registry.add("microcloud.provisioning.task-timeout-seconds") { "180" }
            System.getenv("MICROCLOUD_PVE_SSH_PUBKEY")?.let { v ->
                registry.add("microcloud.provisioning.root-ssh-public-key") { v }
            }
            System.getenv("MICROCLOUD_PVE_SSH_KEY_PATH")?.let { v ->
                registry.add("microcloud.provisioning.ssh-private-key-path") { v }
            }
            System.getenv("MICROCLOUD_PVE_INIT_CMD")?.let { v ->
                registry.add("microcloud.provisioning.init-command") { v }
            }
        }
    }

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

        // If a template source is given, we upload it to the placement and provision from it;
        // otherwise we fall back to MICROCLOUD_PVE_OSTEMPLATE.
        templateId =
            templateRepository
                .save(
                    MachineTemplate(
                        name = "live-${System.nanoTime()}",
                        kind = MachineTemplateKind.LXC,
                        source = System.getenv("MICROCLOUD_PVE_TEMPLATE_SOURCE"),
                    )
                )
                .id!!

        clusterId =
            JSONObject(
                    post(
                            "/machine/proxmox",
                            adminToken,
                            JSONObject()
                                .put("name", "live")
                                .put("apiUrl", env("MICROCLOUD_PVE_URL"))
                                .put("tokenId", env("MICROCLOUD_PVE_TOKEN_ID"))
                                .put("tokenSecret", env("MICROCLOUD_PVE_TOKEN_SECRET"))
                                .put("verifyTls", false)
                                .toString(),
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
                            JSONObject()
                                .put("name", "live-p")
                                .put("clusterId", clusterId)
                                .put("node", env("MICROCLOUD_PVE_NODE"))
                                .put("pool", env("MICROCLOUD_PVE_POOL"))
                                .put("storage", env("MICROCLOUD_PVE_STORAGE"))
                                .toString(),
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")

        // Upload the template image onto the placement's storage, then wait for it to be DONE, so
        // provisioning uses the real uploaded volid (the whole "fresh pool" auto-setup).
        if (System.getenv("MICROCLOUD_PVE_TEMPLATE_SOURCE") != null) {
            post(
                "/machine/template/$templateId/upload",
                adminToken,
                JSONObject().put("placementId", placementId).toString(),
            )
            waitForUploadDone(templateId, 900)
        }

        post(
            "/machine/network",
            adminToken,
            JSONObject()
                .put("placementId", placementId)
                .put("startIp", env("MICROCLOUD_PVE_IP_START"))
                .put("endIp", env("MICROCLOUD_PVE_IP_END"))
                .put("gateway", env("MICROCLOUD_PVE_GATEWAY"))
                .put("prefixLength", env("MICROCLOUD_PVE_PREFIX").toInt())
                .put("bridge", env("MICROCLOUD_PVE_BRIDGE"))
                .toString(),
        )
        val typeId =
            JSONObject(
                    post(
                            "/machine/type",
                            adminToken,
                            JSONObject()
                                .put("name", "live-t")
                                .put("placementIds", JSONArray().put(placementId))
                                .put("coresMin", 1)
                                .put("coresMax", 4)
                                .put("memoryMbMin", 512)
                                .put("memoryMbMax", 8192)
                                .put("diskGbMin", 4)
                                .put("diskGbMax", 100)
                                .toString(),
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
                            JSONObject()
                                .put("name", "live-z")
                                .put("placementIds", JSONArray().put(placementId))
                                .toString(),
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")

        val tenantId =
            JSONObject(
                    post("/tenant", adminToken, """{"name":"live-tenant"}""")
                        .response
                        .contentAsString
                )
                .getLong("id")
        val offeringId =
            JSONObject(
                    post(
                            "/machine/offering",
                            adminToken,
                            JSONObject()
                                .put("tenantId", tenantId)
                                .put("machineTypeId", typeId)
                                .put("zoneId", zoneId)
                                .put("templateId", templateId)
                                .toString(),
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
        secret =
            JSONObject(post("/tenant/$tenantId/secret", adminToken, "{}").response.contentAsString)
                .getString("secret")
        val customerId =
            JSONObject(
                    post("/customer", secret, """{"externalRef":"live-user"}""")
                        .response
                        .contentAsString
                )
                .getLong("id")
        val accountId =
            JSONObject(
                    post("/account", secret, """{"customerId":$customerId,"name":"main"}""")
                        .response
                        .contentAsString
                )
                .getLong("id")

        machineId =
            JSONObject(
                    post(
                            "/machine",
                            secret,
                            JSONObject()
                                .put("customerId", customerId)
                                .put("accountId", accountId)
                                .put("hostname", "live-machine")
                                .put("offeringId", offeringId)
                                .put("cores", 2)
                                .put("memoryMb", 1024)
                                .put("diskGb", 8)
                                .put("user", "dev")
                                .put(
                                    "sshPubkey",
                                    System.getenv("MICROCLOUD_PVE_SSH_PUBKEY")
                                        ?: "ssh-ed25519 AAAA dev",
                                )
                                .toString(),
                        )
                        .response
                        .contentAsString
                )
                .getLong("id")
    }

    /** Poll the template's upload rows until the first one is DONE; return its volid. */
    private fun waitForUploadDone(templateId: Long, timeoutSeconds: Int): String {
        var waited = 0
        while (waited < timeoutSeconds) {
            val items =
                JSONObject(
                        mockMvc
                            .perform(
                                get("/machine/template/$templateId/upload")
                                    .header("Authorization", "Bearer $adminToken")
                            )
                            .andReturn()
                            .response
                            .contentAsString
                    )
                    .getJSONArray("items")
            if (items.length() > 0) {
                val row = items.getJSONObject(0)
                val s = row.getString("status")
                if (s == "done") return row.getString("volid")
                check(s != "error") { "template upload failed: ${row.optString("jobLog")}" }
            }
            Thread.sleep(5000)
            waited += 5
        }
        error("template upload did not finish within ${timeoutSeconds}s")
    }

    private fun machineStatus(): String =
        JSONObject(
                mockMvc
                    .perform(get("/machine/$machineId").header("Authorization", "Bearer $secret"))
                    .andReturn()
                    .response
                    .contentAsString
            )
            .getString("status")

    private fun waitForStatus(target: String, timeoutSeconds: Int) {
        var waited = 0
        while (waited < timeoutSeconds) {
            val s = machineStatus()
            if (s == target) return
            check(s != "error") { "machine went to ERROR while waiting for $target" }
            Thread.sleep(3000)
            waited += 3
        }
        error("machine did not reach $target within ${timeoutSeconds}s (last: ${machineStatus()})")
    }

    @Test
    fun fullLifecycleAgainstRealProxmox() {
        // The async worker creates + starts the LXC on Proxmox; watch it become RUNNING.
        waitForStatus("running", 240)
        assertEquals("running", machineStatus())

        // Every mutation is async now: it returns 202, and we poll GET for the terminal state.
        mockMvc
            .perform(post("/machine/$machineId/stop").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("stopping"))
        waitForStatus("stopped", 120)

        mockMvc
            .perform(post("/machine/$machineId/start").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("starting"))
        waitForStatus("running", 120)

        // Destroy -> Proxmox pct destroy + IP release; poll until the machine is gone (404).
        mockMvc
            .perform(delete("/machine/$machineId").header("Authorization", "Bearer $secret"))
            .andExpect(status().isAccepted)
        var waited = 0
        while (waited < 120) {
            val code =
                mockMvc
                    .perform(get("/machine/$machineId").header("Authorization", "Bearer $secret"))
                    .andReturn()
                    .response
                    .status
            if (code == 404) break
            Thread.sleep(3000)
            waited += 3
        }
        mockMvc
            .perform(get("/machine/$machineId").header("Authorization", "Bearer $secret"))
            .andExpect(status().isNotFound)

        assertNotEquals(-1, machineId)
    }
}
