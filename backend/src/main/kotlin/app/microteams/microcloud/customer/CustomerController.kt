/*
 *  Description: The customer module's one controller — a tenant's self-service management of its
 *               customers. Every method implements a CustomerApi operation; the tenant may only
 *               touch its own customers (the `owned` predicate resolves a customer to its tenant).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.customer

import app.microteams.microcloud.api.CustomerApi
import app.microteams.microcloud.model.*
import javax.annotation.PostConstruct
import org.rucca.cheese.auth.AuthenticationService
import org.rucca.cheese.auth.AuthorizationService
import org.rucca.cheese.auth.AuthorizedAction
import org.rucca.cheese.auth.annotation.AuthInfo
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.cheese.auth.annotation.ResourceId
import org.rucca.cheese.common.persistent.IdGetter
import org.rucca.cheese.common.persistent.IdType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class CustomerController(
    private val customerService: CustomerService,
    private val authenticationService: AuthenticationService,
    private val authorizationService: AuthorizationService,
) : CustomerApi {
    @PostConstruct
    fun initialize() {
        authorizationService.ownerIds.register("customer", customerService::getOwnerTenant)
        // The customer enumeration carries no cross-tenant reference, so listing is always scoped
        // to
        // the caller's own tenant (applied in the service). This predicate is where future
        // cross-scope filters would add their "…belongs to my tenant" conditions.
        register("is-enumerating-own-customers") { _, _ -> true }
    }

    private fun register(name: String, fact: (IdType, Map<String, Any>) -> Boolean) {
        authorizationService.customAuthLogics.register(name) {
            userId: IdType,
            _: AuthorizedAction,
            _: String,
            _: IdType?,
            authInfo: Map<String, Any>,
            _: IdGetter?,
            _: Any? ->
            fact(userId, authInfo)
        }
    }

    private fun tenantId(): IdType = authenticationService.getCurrentUserId()

    @Guard("list-customers", "customer")
    override fun listCustomers(
        pageStart: Long?,
        pageSize: Int,
        @AuthInfo("externalRef") externalRef: String?,
    ): ResponseEntity<ListCustomersResponseDTO> {
        val (items, page) =
            customerService.listCustomers(tenantId(), externalRef, pageStart, pageSize)
        return ResponseEntity.ok(ListCustomersResponseDTO(items = items, page = page))
    }

    @Guard("create-customer", "customer")
    override fun createCustomer(
        @RequestBody createCustomerRequestDTO: CreateCustomerRequestDTO
    ): ResponseEntity<CustomerDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(customerService.createCustomer(tenantId(), createCustomerRequestDTO.externalRef))

    @Guard("get-customer", "customer")
    override fun getCustomer(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<CustomerDTO> =
        ResponseEntity.ok(customerService.getCustomerDTO(tenantId(), id))

    @Guard("delete-customer", "customer")
    override fun deleteCustomer(@PathVariable("id") @ResourceId id: IdType): ResponseEntity<Unit> {
        customerService.deleteCustomer(tenantId(), id)
        return ResponseEntity(HttpStatus.NO_CONTENT)
    }
}
