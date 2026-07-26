package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * one balance change on an account
 *
 * @param id
 * @param accountId
 * @param amount signed delta: positive = credit, negative = debit
 * @param balanceBefore
 * @param balanceAfter
 * @param remark
 * @param createdAt
 */
data class AccountLedgerEntryDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("accountId", required = true)
    val accountId: kotlin.Long,
    @Schema(
        example = "null",
        required = true,
        description = "signed delta: positive = credit, negative = debit",
    )
    @get:JsonProperty("amount", required = true)
    val amount: java.math.BigDecimal,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("balanceBefore", required = true)
    val balanceBefore: java.math.BigDecimal,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("balanceAfter", required = true)
    val balanceAfter: java.math.BigDecimal,
    @Schema(example = "null", description = "")
    @get:JsonProperty("remark")
    val remark: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
