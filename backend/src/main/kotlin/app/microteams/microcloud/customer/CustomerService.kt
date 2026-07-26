/*
 *  Description: CRUD for a tenant's customers. Every query is scoped to the calling tenant.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.customer

import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.model.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun Customer.toDTO() =
    CustomerDTO(
        id = this.id!!,
        externalRef = this.externalRef!!,
        status =
            when (this.status) {
                CustomerStatus.ACTIVE -> CustomerStatusDTO.active
                CustomerStatus.SUSPENDED -> CustomerStatusDTO.suspended
            },
        createdAt = this.createdAt?.atOffset(ZoneOffset.UTC),
    )

@Service
@Transactional
class CustomerService(private val customerRepository: CustomerRepository) {
    fun getCustomer(tenantId: IdType, id: IdType): Customer {
        val customer = customerRepository.findById(id).orElseThrow { NotFoundError("customer", id) }
        if (customer.tenantId != tenantId) throw NotFoundError("customer", id)
        return customer
    }

    fun getCustomerDTO(tenantId: IdType, id: IdType): CustomerDTO =
        getCustomer(tenantId, id).toDTO()

    fun createCustomer(tenantId: IdType, externalRef: String): CustomerDTO =
        customerRepository.save(Customer(tenantId = tenantId, externalRef = externalRef)).toDTO()

    fun listCustomers(
        tenantId: IdType,
        externalRef: String?,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<CustomerDTO>, PageDTO> {
        val all =
            (if (externalRef.isNullOrBlank()) customerRepository.findByTenantId(tenantId)
                else
                    customerRepository.findByTenantIdAndExternalRefContaining(
                        tenantId,
                        externalRef,
                    ))
                .sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun deleteCustomer(tenantId: IdType, id: IdType) {
        val customer = getCustomer(tenantId, id)
        customer.deletedAt = LocalDateTime.now()
        customerRepository.save(customer)
    }

    /** Owner resolver for the `owned` predicate: a customer is owned by its tenant. */
    fun getOwnerTenant(id: IdType): IdType =
        customerRepository.findById(id).orElseThrow { NotFoundError("customer", id) }.tenantId!!
}
