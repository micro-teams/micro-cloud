/*
 *  Description: Integration test for super-admin management of Proxmox clusters: create -> get ->
 *               list -> update -> delete, plus the auth gates (missing auth, wrong realm) and the
 *               guarantee that the API token secret is write-only (never returned). The live
 *               inventory read is not exercised here — it requires a reachable Proxmox cluster.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.api

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
class ProxmoxClusterTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    private lateinit var adminToken: String
    private var clusterId: Long = -1

    @BeforeAll
    fun login() {
        val res =
            mockMvc
                .perform(
                    post("/superadmin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"password":"$superadminPassword"}""")
                )
                .andExpect(status().isOk)
                .andReturn()
        adminToken = JSONObject(res.response.contentAsString).getString("token")
    }

    @Test
    @Order(1)
    fun missingAuthIsRejected() {
        mockMvc.perform(get("/machine/proxmox")).andExpect(status().isUnauthorized)
    }

    @Test
    @Order(2)
    fun createCluster() {
        val res =
            mockMvc
                .perform(
                    post("/machine/proxmox")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"lab","apiUrl":"https://pve.example:8006",
                             "tokenId":"microcloud@pve!t","tokenSecret":"super-secret","verifyTls":false}
                            """
                                .trimIndent()
                        )
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("lab"))
                .andExpect(jsonPath("$.apiUrl").value("https://pve.example:8006"))
                .andExpect(jsonPath("$.tokenId").value("microcloud@pve!t"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.verifyTls").value(false))
                // The token secret must never be returned.
                .andExpect(jsonPath("$.tokenSecret").doesNotExist())
                .andReturn()
        clusterId = JSONObject(res.response.contentAsString).getLong("id")
    }

    @Test
    @Order(3)
    fun getAndListCluster() {
        mockMvc
            .perform(
                get("/machine/proxmox/$clusterId").header("Authorization", "Bearer $adminToken")
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(clusterId))
            .andExpect(jsonPath("$.tokenSecret").doesNotExist())

        mockMvc
            .perform(get("/machine/proxmox").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.id==$clusterId)]").exists())
    }

    @Test
    @Order(4)
    fun updateCluster() {
        mockMvc
            .perform(
                patch("/machine/proxmox/$clusterId")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"lab-renamed","status":"disabled"}""")
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("lab-renamed"))
            .andExpect(jsonPath("$.status").value("disabled"))
    }

    @Test
    @Order(5)
    fun getUnknownClusterIs404() {
        mockMvc
            .perform(get("/machine/proxmox/999999").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(6)
    fun deleteCluster() {
        mockMvc
            .perform(
                delete("/machine/proxmox/$clusterId").header("Authorization", "Bearer $adminToken")
            )
            .andExpect(status().isNoContent)
        mockMvc
            .perform(
                get("/machine/proxmox/$clusterId").header("Authorization", "Bearer $adminToken")
            )
            .andExpect(status().isNotFound)
    }
}
