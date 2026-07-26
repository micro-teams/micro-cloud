/*
 *  Description: The Customer entity and repository. A customer belongs to one tenant and maps to one
 *               upstream user via externalRef; it owns accounts and machines.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.customer

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class CustomerStatus {
    ACTIVE,
    SUSPENDED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "customer",
    indexes = [Index(columnList = "tenant_id"), Index(columnList = "tenant_id, external_ref")],
)
class Customer(
    @Column(name = "tenant_id", nullable = false) var tenantId: IdType? = null,
    @Column(name = "external_ref", nullable = false) var externalRef: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: CustomerStatus = CustomerStatus.ACTIVE,
) : BaseEntity()

interface CustomerRepository : JpaRepository<Customer, IdType> {
    fun findByTenantId(tenantId: IdType): List<Customer>

    fun findByTenantIdAndExternalRefContaining(
        tenantId: IdType,
        externalRef: String,
    ): List<Customer>
}
