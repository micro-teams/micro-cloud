/*
 *  Description: The Account (fund account) entity and repository. A pure-number balance (no currency
 *               unit), owned by a customer under a tenant. Machines and api-keys bill against one
 *               account; the balance never goes negative.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.account

import jakarta.persistence.*
import java.math.BigDecimal
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "account",
    indexes = [Index(columnList = "tenant_id"), Index(columnList = "customer_id")],
)
class Account(
    @Column(name = "tenant_id", nullable = false) var tenantId: IdType? = null,
    @Column(name = "customer_id", nullable = false) var customerId: IdType? = null,
    @Column(nullable = false) var name: String? = null,
    @Column(nullable = false, precision = 20, scale = 6) var balance: BigDecimal = BigDecimal.ZERO,
) : BaseEntity()

interface AccountRepository : JpaRepository<Account, IdType> {
    fun findByTenantId(tenantId: IdType): List<Account>

    fun findByTenantIdAndCustomerId(tenantId: IdType, customerId: IdType): List<Account>
}
