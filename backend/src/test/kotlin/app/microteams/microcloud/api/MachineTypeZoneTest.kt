/*
 *  Description: Integration test for the machine-type and zone catalog: admin creates a cluster ->
 *               placement -> a machine type and a zone over that placement; a tenant may read them
 *               but not write; spec-range and referential validation are enforced.
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
class MachineTypeZoneTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    private lateinit var adminToken: String
    private lateinit var tenantSecret: String
    private var placementId: Long = -1
    private var typeId: Long = -1

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
                            """{"name":"p1","clusterId":$clusterId,"node":"pve","pool":"microcloud","storage":"local-lvm"}"""
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
    fun typeOnUnknownPlacementIs404() {
        mockMvc
            .perform(
                post("/machine/type")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"t","placementIds":[999999],"coresMin":1,"coresMax":4,"memoryMbMin":1024,"memoryMbMax":8192,"diskGbMin":10,"diskGbMax":100}"""
                    )
            )
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(2)
    fun invalidRangeIsRejected() {
        mockMvc
            .perform(
                post("/machine/type")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"t","placementIds":[$placementId],"coresMin":8,"coresMax":4,"memoryMbMin":1024,"memoryMbMax":8192,"diskGbMin":10,"diskGbMax":100}"""
                    )
            )
            .andExpect(status().isBadRequest)
    }

    @Test
    @Order(3)
    fun adminCreatesType() {
        val res =
            mockMvc
                .perform(
                    post("/machine/type")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"standard","placementIds":[$placementId],"coresMin":1,"coresMax":4,"memoryMbMin":1024,"memoryMbMax":8192,"diskGbMin":10,"diskGbMax":100}"""
                        )
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("standard"))
                .andExpect(jsonPath("$.placementIds[0]").value(placementId))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn()
        typeId = JSONObject(res.response.contentAsString).getLong("id")
    }

    @Test
    @Order(4)
    fun tenantCannotReadOrWriteType() {
        mockMvc
            .perform(get("/machine/type").header("Authorization", "Bearer $tenantSecret"))
            .andExpect(status().isForbidden)

        // A tenant secret does not carry the write action -> forbidden.
        mockMvc
            .perform(
                post("/machine/type")
                    .header("Authorization", "Bearer $tenantSecret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"x","placementIds":[$placementId],"coresMin":1,"coresMax":2,"memoryMbMin":512,"memoryMbMax":1024,"diskGbMin":5,"diskGbMax":10}"""
                    )
            )
            .andExpect(status().isForbidden)
    }

    @Test
    @Order(5)
    fun adminCreatesZoneTenantCannotRead() {
        val res =
            mockMvc
                .perform(
                    post("/machine/zone")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"z-a","placementIds":[$placementId]}""")
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("z-a"))
                .andReturn()
        val zoneId = JSONObject(res.response.contentAsString).getLong("id")

        mockMvc
            .perform(get("/machine/zone/$zoneId").header("Authorization", "Bearer $tenantSecret"))
            .andExpect(status().isForbidden)

        mockMvc
            .perform(
                post("/machine/zone")
                    .header("Authorization", "Bearer $tenantSecret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"nope","placementIds":[$placementId]}""")
            )
            .andExpect(status().isForbidden)
    }
}
