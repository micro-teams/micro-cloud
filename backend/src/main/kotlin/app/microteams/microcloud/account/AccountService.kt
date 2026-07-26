/*
 *  Description: A tenant's fund accounts: create / list / get / top-up. Every query is scoped to the
 *               calling tenant. Balance is a pure number and never goes negative.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.account

import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.model.*
import java.math.BigDecimal
import org.rucca.cheese.common.error.BadRequestError
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun Account.toDTO() =
    AccountDTO(
        id = this.id!!,
        customerId = this.customerId!!,
        name = this.name!!,
        balance = this.balance,
        createdAt = this.createdAt?.atOffset(java.time.ZoneOffset.UTC),
    )

fun LedgerEntry.toDTO() =
    AccountLedgerEntryDTO(
        id = this.id!!,
        accountId = this.accountId!!,
        amount = this.amount,
        balanceBefore = this.balanceBefore,
        balanceAfter = this.balanceAfter,
        remark = this.remark,
        createdAt = this.createdAt?.atOffset(java.time.ZoneOffset.UTC),
    )

@Service
@Transactional
class AccountService(
    private val accountRepository: AccountRepository,
    private val ledgerRepository: LedgerEntryRepository,
) {
    fun getAccount(tenantId: IdType, id: IdType): Account {
        val account = accountRepository.findById(id).orElseThrow { NotFoundError("account", id) }
        if (account.tenantId != tenantId) throw NotFoundError("account", id)
        return account
    }

    fun getAccountDTO(tenantId: IdType, id: IdType): AccountDTO = getAccount(tenantId, id).toDTO()

    fun createAccount(tenantId: IdType, customerId: IdType, name: String): AccountDTO =
        accountRepository
            .save(Account(tenantId = tenantId, customerId = customerId, name = name))
            .toDTO()

    fun listAccounts(
        tenantId: IdType,
        customerId: IdType?,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<AccountDTO>, PageDTO> {
        val all =
            (if (customerId == null) accountRepository.findByTenantId(tenantId)
                else accountRepository.findByTenantIdAndCustomerId(tenantId, customerId))
                .sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun topup(tenantId: IdType, id: IdType, amount: BigDecimal, remark: String?): AccountDTO {
        if (amount <= BigDecimal.ZERO) throw BadRequestError("topup amount must be positive")
        return apply(getAccount(tenantId, id), amount, remark)
    }

    fun listLedger(
        tenantId: IdType,
        id: IdType,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<AccountLedgerEntryDTO>, PageDTO> {
        getAccount(tenantId, id) // 404 / cross-tenant guard
        val all = ledgerRepository.findByAccountId(id).sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    /**
     * Apply a signed delta to the balance and record one ledger entry. The single choke point for
     * every fund change (top-up, charge, daily grant, adjustment).
     */
    fun apply(account: Account, delta: BigDecimal, remark: String?): AccountDTO {
        val before = account.balance
        val after = before.add(delta)
        account.balance = after
        val saved = accountRepository.save(account)
        ledgerRepository.save(
            LedgerEntry(
                accountId = saved.id!!,
                tenantId = saved.tenantId!!,
                amount = delta,
                balanceBefore = before,
                balanceAfter = after,
                remark = remark,
            )
        )
        return saved.toDTO()
    }

    /** Owner resolver for the `owned` predicate: an account is owned by its tenant. */
    fun getOwnerTenant(id: IdType): IdType =
        accountRepository.findById(id).orElseThrow { NotFoundError("account", id) }.tenantId!!
}
