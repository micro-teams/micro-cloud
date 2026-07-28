package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param id
 * @param customerId
 * @param accountId fund account charged for compute
 * @param newapiAccountId fund account charged for newapi AI usage
 * @param ccproxyAccountId fund account charged for ccproxy AI usage
 * @param hostname
 * @param offeringId the offering the machine was created from
 * @param typeId
 * @param templateId
 * @param cores
 * @param memoryMb
 * @param diskGb
 * @param status
 * @param aiMode how this machine's Claude Code gets model access: none | newapi | ccproxy
 * @param aiStatus AI setup state, independent of `status`: disabled | provisioning | ready | error
 * @param zoneId
 * @param apiKeyId the model key whose account the machine's Claude Code bills to (if configured)
 * @param ip private IP once assigned
 * @param createdAt
 */
data class MachineDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("customerId", required = true)
    val customerId: kotlin.Long,
    @Schema(example = "null", required = true, description = "fund account charged for compute")
    @get:JsonProperty("accountId", required = true)
    val accountId: kotlin.Long,
    @Schema(
        example = "null",
        required = true,
        description = "fund account charged for newapi AI usage",
    )
    @get:JsonProperty("newapiAccountId", required = true)
    val newapiAccountId: kotlin.Long,
    @Schema(
        example = "null",
        required = true,
        description = "fund account charged for ccproxy AI usage",
    )
    @get:JsonProperty("ccproxyAccountId", required = true)
    val ccproxyAccountId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("hostname", required = true)
    val hostname: kotlin.String,
    @Schema(
        example = "null",
        required = true,
        description = "the offering the machine was created from",
    )
    @get:JsonProperty("offeringId", required = true)
    val offeringId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("typeId", required = true)
    val typeId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("templateId", required = true)
    val templateId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("cores", required = true)
    val cores: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("memoryMb", required = true)
    val memoryMb: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("diskGb", required = true)
    val diskGb: kotlin.Int,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: MachineStatusDTO,
    @Schema(
        example = "newapi",
        required = true,
        description = "how this machine's Claude Code gets model access: none | newapi | ccproxy",
    )
    @get:JsonProperty("aiMode", required = true)
    val aiMode: kotlin.String,
    @Schema(
        example = "ready",
        required = true,
        description =
            "AI setup state, independent of `status`: disabled | provisioning | ready | error",
    )
    @get:JsonProperty("aiStatus", required = true)
    val aiStatus: kotlin.String,
    @Schema(example = "null", description = "")
    @get:JsonProperty("zoneId")
    val zoneId: kotlin.Long? = null,
    @Schema(
        example = "null",
        description =
            "the model key whose account the machine's Claude Code bills to (if configured)",
    )
    @get:JsonProperty("apiKeyId")
    val apiKeyId: kotlin.Long? = null,
    @Schema(example = "null", description = "private IP once assigned")
    @get:JsonProperty("ip")
    val ip: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
