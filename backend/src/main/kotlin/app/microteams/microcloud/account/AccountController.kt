/*
 *  Description: The account module's one controller — a tenant's self-service management of its fund
 *               accounts. Every method implements an AccountApi operation, scoped to the tenant.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.account

import app.microteams.microcloud.api.AccountApi
import app.microteams.microcloud.customer.CustomerService
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
class AccountController(
    private val accountService: AccountService,
    private val customerService: CustomerService,
    private val authenticationService: AuthenticationService,
    private val authorizationService: AuthorizationService,
) : AccountApi {
    @PostConstruct
    fun initialize() {
        authorizationService.ownerIds.register("account", accountService::getOwnerTenant)
        // Enumeration is scoped to the caller's tenant in the service; a customer_id filter (an
        // optional condition) is allowed only when that customer belongs to the caller.
        register("queried-customer-is-own") { userId, authInfo ->
            when (val cid = authInfo["customerId"] as? Long) {
                null -> true
                else -> customerService.getOwnerTenant(cid) == userId
            }
        }
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

    @Guard("list-accounts", "account")
    override fun listAccounts(
        pageStart: Long?,
        pageSize: Int,
        @AuthInfo("customerId") customerId: Long?,
    ): ResponseEntity<ListAccountsResponseDTO> {
        val (items, page) = accountService.listAccounts(tenantId(), customerId, pageStart, pageSize)
        return ResponseEntity.ok(ListAccountsResponseDTO(items = items, page = page))
    }

    @Guard("create-account", "account")
    override fun createAccount(
        @RequestBody createAccountRequestDTO: CreateAccountRequestDTO
    ): ResponseEntity<AccountDTO> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(
                accountService.createAccount(
                    tenantId(),
                    createAccountRequestDTO.customerId,
                    createAccountRequestDTO.name,
                )
            )

    @Guard("get-account", "account")
    override fun getAccount(
        @PathVariable("id") @ResourceId id: IdType
    ): ResponseEntity<AccountDTO> = ResponseEntity.ok(accountService.getAccountDTO(tenantId(), id))

    @Guard("topup-account", "account")
    override fun topupAccount(
        @PathVariable("id") @ResourceId id: IdType,
        @RequestBody topupAccountRequestDTO: TopupAccountRequestDTO,
    ): ResponseEntity<AccountDTO> =
        ResponseEntity.ok(
            accountService.topup(
                tenantId(),
                id,
                topupAccountRequestDTO.amount,
                topupAccountRequestDTO.remark,
            )
        )

    @Guard("list-account-ledger", "account")
    override fun listAccountLedger(
        @PathVariable("id") @ResourceId id: IdType,
        pageStart: Long?,
        pageSize: Int,
    ): ResponseEntity<ListAccountLedgerResponseDTO> {
        val (items, page) = accountService.listLedger(tenantId(), id, pageStart, pageSize)
        return ResponseEntity.ok(ListAccountLedgerResponseDTO(items = items, page = page))
    }
}
