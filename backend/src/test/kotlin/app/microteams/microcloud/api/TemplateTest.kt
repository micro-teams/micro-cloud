/*
 *  Description: Integration test for the template catalog + per-placement upload tracking. A template
 *               is seeded like build time (directly into the catalog); a tenant may list it but not
 *               upload; an admin uploads it to a placement and the per-(template, placement) row is
 *               tracked and idempotent.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.api

import app.microteams.microcloud.machine.MachineKind
import app.microteams.microcloud.machine.template.MachineTemplate
import app.microteams.microcloud.machine.template.MachineTemplateRepository
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureMockMvc
@TestMethodOrder(OrderAnnotation::class)
class TemplateTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    private val templateRepository: MachineTemplateRepository,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    private lateinit var adminToken: String
    private lateinit var tenantSecret: String
    private var templateId: Long = -1
    private var placementId: Long = -1

    @BeforeAll
    fun setup() {
        val login =
            mockMvc
                .perform(
                    post("/superadmin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"password":"$superadminPassword"}""")
                )
                .andReturn()
        adminToken = JSONObject(login.response.contentAsString).getString("token")

        // A template exists independently of the API (built at build time; seeded here). A unique
        // name keeps local reruns against a persistent DB isolated (unique-name constraint + its
        // own
        // upload rows).
        templateId =
            templateRepository
                .save(
                    MachineTemplate(
                        name = "debian13-test-${System.nanoTime()}",
                        kind = MachineKind.PROXMOX_LXC,
                    )
                )
                .id!!

        val cluster =
            mockMvc
                .perform(
                    post("/machine/proxmox")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"lab","apiUrl":"https://pve:8006","tokenId":"m@pve!t","tokenSecret":"s"}"""
                        )
                )
                .andReturn()
        val clusterId = JSONObject(cluster.response.contentAsString).getLong("id")
        val placement =
            mockMvc
                .perform(
                    post("/machine/placement")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"kind":"proxmox/lxc","name":"p1","clusterId":$clusterId,"node":"pve","pool":"microcloud","storage":"local-lvm"}"""
                        )
                )
                .andReturn()
        placementId = JSONObject(placement.response.contentAsString).getLong("id")

        val tenant =
            mockMvc
                .perform(
                    post("/tenant")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"reader"}""")
                )
                .andReturn()
        val tenantId = JSONObject(tenant.response.contentAsString).getLong("id")
        val secret =
            mockMvc
                .perform(
                    post("/tenant/$tenantId/secret")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{}""")
                )
                .andReturn()
        tenantSecret = JSONObject(secret.response.contentAsString).getString("secret")
    }

    @Test
    @Order(1)
    fun tenantCannotListOrUpload() {
        // Templates are admin-only now; a tenant sees them only embedded in its offerings.
        mockMvc
            .perform(get("/machine/template").header("Authorization", "Bearer $tenantSecret"))
            .andExpect(status().isForbidden)

        mockMvc
            .perform(
                post("/machine/template/$templateId/upload")
                    .header("Authorization", "Bearer $tenantSecret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"placementId":$placementId}""")
            )
            .andExpect(status().isForbidden)
    }

    @Test
    @Order(2)
    fun uploadOnUnknownPlacementIs404() {
        mockMvc
            .perform(
                post("/machine/template/$templateId/upload")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"placementId":999999}""")
            )
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(3)
    fun adminUploadsAndItIsTracked() {
        mockMvc
            .perform(
                post("/machine/template/$templateId/upload")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"placementId":$placementId}""")
            )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.templateId").value(templateId))
            .andExpect(jsonPath("$.placementId").value(placementId))
            .andExpect(jsonPath("$.status").value("pending"))

        // Upload is idempotent per (template, placement): a second call yields one row, not two.
        mockMvc
            .perform(
                post("/machine/template/$templateId/upload")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"placementId":$placementId}""")
            )
            .andExpect(status().isAccepted)

        mockMvc
            .perform(
                get("/machine/template/$templateId/upload")
                    .header("Authorization", "Bearer $adminToken")
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].placementId").value(placementId))
    }
}
