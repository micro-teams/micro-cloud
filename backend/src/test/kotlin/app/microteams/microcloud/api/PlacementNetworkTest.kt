/*
 *  Description: Integration test for the super-admin placement + network chain: create a Proxmox
 *               cluster -> a placement on it -> a network bound to that placement, then exercise the
 *               range math (totalCount), the referential guards (unknown cluster / placement ->
 *               404), input validation (bad IP range -> 400), and the placement filter on listing.
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
class PlacementNetworkTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    private lateinit var adminToken: String
    private var clusterId: Long = -1
    private var placementId: Long = -1
    private var networkId: Long = -1

    @BeforeAll
    fun setup() {
        val login =
            mockMvc
                .perform(
                    post("/superadmin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"password":"$superadminPassword"}""")
                )
                .andExpect(status().isOk)
                .andReturn()
        adminToken = JSONObject(login.response.contentAsString).getString("token")

        val cluster =
            mockMvc
                .perform(
                    post("/machine/proxmox")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"lab","apiUrl":"https://pve.example:8006","tokenId":"m@pve!t","tokenSecret":"s"}"""
                        )
                )
                .andExpect(status().isCreated)
                .andReturn()
        clusterId = JSONObject(cluster.response.contentAsString).getLong("id")
    }

    @Test
    @Order(1)
    fun placementOnUnknownClusterIs404() {
        mockMvc
            .perform(
                post("/machine/placement")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"kind":"proxmox/lxc","name":"p","clusterId":999999,"node":"pve","pool":"microcloud","storage":"local-lvm"}"""
                    )
            )
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(2)
    fun createPlacement() {
        val res =
            mockMvc
                .perform(
                    post("/machine/placement")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"kind":"proxmox/lxc","name":"p1","clusterId":$clusterId,"node":"pve","pool":"microcloud","storage":"local-lvm"}"""
                        )
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.kind").value("proxmox/lxc"))
                .andExpect(jsonPath("$.clusterId").value(clusterId))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn()
        placementId = JSONObject(res.response.contentAsString).getLong("id")
    }

    @Test
    @Order(3)
    fun networkOnUnknownPlacementIs404() {
        mockMvc
            .perform(
                post("/machine/network")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"placementId":999999,"startIp":"10.0.0.10","endIp":"10.0.0.20","gateway":"10.0.0.1","prefixLength":24,"bridge":"vmbr0"}"""
                    )
            )
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(4)
    fun invalidRangeIsRejected() {
        mockMvc
            .perform(
                post("/machine/network")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"placementId":$placementId,"startIp":"10.0.0.20","endIp":"10.0.0.10","gateway":"10.0.0.1","prefixLength":24,"bridge":"vmbr0"}"""
                    )
            )
            .andExpect(status().isBadRequest)
    }

    @Test
    @Order(5)
    fun createNetworkReportsRangeSize() {
        val res =
            mockMvc
                .perform(
                    post("/machine/network")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"n1","placementId":$placementId,"startIp":"10.0.0.10","endIp":"10.0.0.19","gateway":"10.0.0.1","prefixLength":24,"bridge":"vmbr0"}"""
                        )
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.placementId").value(placementId))
                .andExpect(jsonPath("$.totalCount").value(10))
                .andExpect(jsonPath("$.allocatedCount").value(0))
                .andReturn()
        networkId = JSONObject(res.response.contentAsString).getLong("id")
    }

    @Test
    @Order(6)
    fun listNetworksFilteredByPlacement() {
        mockMvc
            .perform(
                get("/machine/network")
                    .header("Authorization", "Bearer $adminToken")
                    .param("placement_id", placementId.toString())
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value(networkId))
    }

    @Test
    @Order(7)
    fun disableNetwork() {
        mockMvc
            .perform(
                patch("/machine/network/$networkId")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"status":"disabled"}""")
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("disabled"))
    }

    @Test
    @Order(8)
    fun deleteChain() {
        mockMvc
            .perform(
                delete("/machine/network/$networkId").header("Authorization", "Bearer $adminToken")
            )
            .andExpect(status().isNoContent)
        mockMvc
            .perform(
                delete("/machine/placement/$placementId")
                    .header("Authorization", "Bearer $adminToken")
            )
            .andExpect(status().isNoContent)
    }
}
