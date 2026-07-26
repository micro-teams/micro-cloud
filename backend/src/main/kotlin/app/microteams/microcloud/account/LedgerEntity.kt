/*
 *  Description: The account ledger: one immutable row per balance change (charge, top-up, daily
 *               grant, adjustment) with the signed amount and the before/after balance.
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
@Table(name = "account_ledger", indexes = [Index(columnList = "account_id")])
class LedgerEntry(
    @Column(name = "account_id", nullable = false) var accountId: IdType? = null,
    @Column(name = "tenant_id", nullable = false) var tenantId: IdType? = null,
    @Column(nullable = false, precision = 20, scale = 6) var amount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "balance_before", nullable = false, precision = 20, scale = 6)
    var balanceBefore: BigDecimal = BigDecimal.ZERO,
    @Column(name = "balance_after", nullable = false, precision = 20, scale = 6)
    var balanceAfter: BigDecimal = BigDecimal.ZERO,
    @Column(length = 512) var remark: String? = null,
) : BaseEntity()

interface LedgerEntryRepository : JpaRepository<LedgerEntry, IdType> {
    fun findByAccountId(accountId: IdType): List<LedgerEntry>
}
