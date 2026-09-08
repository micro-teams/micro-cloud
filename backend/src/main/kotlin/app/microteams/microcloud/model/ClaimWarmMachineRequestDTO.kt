package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.constraints.Size

/**
 * @param claimKey Stable recipient identity used for retries
 * @param customerId
 * @param accountId
 * @param newapiAccountId
 * @param ccproxyAccountId
 */
data class ClaimWarmMachineRequestDTO(
    @get:Size(min = 1, max = 128)
    @Schema(
        example = "null",
        required = true,
        description = "Stable recipient identity used for retries",
    )
    @get:JsonProperty("claimKey", required = true)
    val claimKey: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("customerId", required = true)
    val customerId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("accountId", required = true)
    val accountId: kotlin.Long,
    @Schema(example = "null", description = "")
    @get:JsonProperty("newapiAccountId")
    val newapiAccountId: kotlin.Long? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("ccproxyAccountId")
    val ccproxyAccountId: kotlin.Long? = null,
) {}
