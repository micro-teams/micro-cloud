/*
 *  Description: Integration test for the tenant-facing surface, end to end through the full stack:
 *               super-admin login -> create a tenant -> mint an auth secret -> the tenant uses that
 *               secret to manage customers and fund accounts. Also checks the auth gates (wrong
 *               password, missing auth, wrong-realm, cross-tenant isolation).
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
class TenantFlowTest
@Autowired
constructor(
    private val mockMvc: MockMvc,
    @Value("\${microcloud.superadmin-password}") private val superadminPassword: String,
) {
    private lateinit var adminToken: String
    private var tenantId: Long = -1
    private lateinit var secret: String
    private var customerId: Long = -1
    private var accountId: Long = -1

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
    fun wrongPasswordIsRejected() {
        mockMvc
            .perform(
                post("/superadmin/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"password":"definitely-not-the-password"}""")
            )
            .andExpect(status().isUnauthorized)
    }

    @Test
    @Order(2)
    fun createTenant() {
        val res =
            mockMvc
                .perform(
                    post("/tenant")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"cheese-ruc"}""")
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("cheese-ruc"))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn()
        tenantId = JSONObject(res.response.contentAsString).getLong("id")
    }

    @Test
    @Order(3)
    fun mintSecret() {
        val res =
            mockMvc
                .perform(
                    post("/tenant/$tenantId/secret")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"label":"prod"}""")
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.secret").exists())
                .andReturn()
        secret = JSONObject(res.response.contentAsString).getString("secret")
    }

    @Test
    @Order(4)
    fun missingAuthIsRejected() {
        mockMvc.perform(get("/customer")).andExpect(status().isUnauthorized)
    }

    @Test
    @Order(5)
    fun superAdminMayNotManageCustomers() {
        // customer/account are tenant-scoped; the super-admin role does not grant them.
        mockMvc
            .perform(get("/customer").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isForbidden)
    }

    @Test
    @Order(6)
    fun tenantCreatesCustomer() {
        val res =
            mockMvc
                .perform(
                    post("/customer")
                        .header("Authorization", "Bearer $secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"externalRef":"ruc-user-42"}""")
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.externalRef").value("ruc-user-42"))
                .andReturn()
        customerId = JSONObject(res.response.contentAsString).getLong("id")
    }

    @Test
    @Order(7)
    fun tenantCreatesAndTopsUpAccount() {
        val created =
            mockMvc
                .perform(
                    post("/account")
                        .header("Authorization", "Bearer $secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"customerId":$customerId,"name":"paid"}""")
                )
                .andExpect(status().isCreated)
                .andReturn()
        accountId = JSONObject(created.response.contentAsString).getLong("id")

        mockMvc
            .perform(
                post("/account/$accountId/topup")
                    .header("Authorization", "Bearer $secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amount":100}""")
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.balance").value(100))

        // the top-up is recorded in the account ledger with before/after balances
        mockMvc
            .perform(get("/account/$accountId/ledger").header("Authorization", "Bearer $secret"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].amount").value(100))
            .andExpect(jsonPath("$.items[0].balanceBefore").value(0))
            .andExpect(jsonPath("$.items[0].balanceAfter").value(100))
    }

    @Test
    @Order(8)
    fun negativeTopupIsRejected() {
        mockMvc
            .perform(
                post("/account/$accountId/topup")
                    .header("Authorization", "Bearer $secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amount":-5}""")
            )
            .andExpect(status().isBadRequest)
    }

    @Test
    @Order(9)
    fun tenantListsItsCustomersAndAccounts() {
        mockMvc
            .perform(get("/customer").header("Authorization", "Bearer $secret"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value(customerId))

        mockMvc
            .perform(
                get("/account")
                    .header("Authorization", "Bearer $secret")
                    .param("customer_id", customerId.toString())
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value(accountId))
    }

    @Test
    @Order(10)
    fun anotherTenantCannotSeeThisTenantsCustomer() {
        // Make a second tenant + secret, and confirm it cannot read the first tenant's customer.
        val t2 =
            mockMvc
                .perform(
                    post("/tenant")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"cheese-etrip"}""")
                )
                .andReturn()
        val t2Id = JSONObject(t2.response.contentAsString).getLong("id")
        val s2 =
            mockMvc
                .perform(
                    post("/tenant/$t2Id/secret")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{}""")
                )
                .andReturn()
        val secret2 = JSONObject(s2.response.contentAsString).getString("secret")

        mockMvc
            .perform(get("/customer/$customerId").header("Authorization", "Bearer $secret2"))
            .andExpect(status().isForbidden)

        // And listing accounts filtered by the first tenant's customer is refused.
        mockMvc
            .perform(
                get("/account")
                    .header("Authorization", "Bearer $secret2")
                    .param("customer_id", customerId.toString())
            )
            .andExpect(status().isForbidden)
    }

    @Test
    @Order(11)
    fun revokedSecretStopsWorking() {
        // list the tenant's secrets, revoke the working one, confirm it no longer authenticates.
        val list =
            mockMvc
                .perform(
                    get("/tenant/$tenantId/secret").header("Authorization", "Bearer $adminToken")
                )
                .andExpect(status().isOk)
                .andReturn()
        val secretId =
            JSONObject(list.response.contentAsString)
                .getJSONArray("items")
                .getJSONObject(0)
                .getLong("id")
        mockMvc
            .perform(
                delete("/tenant/$tenantId/secret/$secretId")
                    .header("Authorization", "Bearer $adminToken")
            )
            .andExpect(status().isNoContent)
        // The revoked secret is now unknown -> the filter does not authenticate -> 401.
        mockMvc
            .perform(get("/customer").header("Authorization", "Bearer $secret"))
            .andExpect(status().isUnauthorized)
    }
}
