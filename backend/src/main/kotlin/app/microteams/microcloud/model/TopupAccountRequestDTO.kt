package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param amount amount to add (pure number, same unit as the account)
 * @param remark
 */
data class TopupAccountRequestDTO(
    @Schema(
        example = "null",
        required = true,
        description = "amount to add (pure number, same unit as the account)",
    )
    @get:JsonProperty("amount", required = true)
    val amount: java.math.BigDecimal,
    @Schema(example = "null", description = "")
    @get:JsonProperty("remark")
    val remark: kotlin.String? = null,
) {}
