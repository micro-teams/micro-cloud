package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param customerId
 * @param accountId fund account to charge for compute
 * @param hostname the machine's hostname
 * @param offeringId the offering (machine type + zone + template) to provision from
 * @param cores within the offering type's coresMin..coresMax
 * @param memoryMb within the offering type's memoryMbMin..memoryMbMax
 * @param diskGb within the offering type's diskGbMin..diskGbMax
 * @param user non-root login user to create on the machine
 * @param newapiAccountId account for newapi AI usage; defaults to accountId if omitted
 * @param ccproxyAccountId account for ccproxy AI usage; defaults to accountId if omitted
 * @param sshPubkey SSH public key to authorize for the user
 * @param apiKeyId (legacy, optional) a pre-created model key to bind; superseded by aiMode
 * @param aiMode How this machine's Claude Code reaches models, decided at create: newapi (default;
 *   a per-machine relay token, usable as soon as the machine runs), ccproxy (the subscription login
 *   starts as soon as the machine runs, a human login-operator completes the OAuth on ccproxy's
 *   side, and aiStatus lands ready when it does; 400 if ccproxy is not configured), or none.
 *   Without this a ccproxy machine had to be created on newapi and switched once running, which
 *   provisioned its AI channel twice.
 */
data class CreateMachineRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("customerId", required = true)
    val customerId: kotlin.Long,
    @Schema(example = "null", required = true, description = "fund account to charge for compute")
    @get:JsonProperty("accountId", required = true)
    val accountId: kotlin.Long,
    @Schema(example = "null", required = true, description = "the machine's hostname")
    @get:JsonProperty("hostname", required = true)
    val hostname: kotlin.String,
    @Schema(
        example = "null",
        required = true,
        description = "the offering (machine type + zone + template) to provision from",
    )
    @get:JsonProperty("offeringId", required = true)
    val offeringId: kotlin.Long,
    @Schema(
        example = "null",
        required = true,
        description = "within the offering type's coresMin..coresMax",
    )
    @get:JsonProperty("cores", required = true)
    val cores: kotlin.Int,
    @Schema(
        example = "null",
        required = true,
        description = "within the offering type's memoryMbMin..memoryMbMax",
    )
    @get:JsonProperty("memoryMb", required = true)
    val memoryMb: kotlin.Int,
    @Schema(
        example = "null",
        required = true,
        description = "within the offering type's diskGbMin..diskGbMax",
    )
    @get:JsonProperty("diskGb", required = true)
    val diskGb: kotlin.Int,
    @Schema(
        example = "null",
        required = true,
        description = "non-root login user to create on the machine",
    )
    @get:JsonProperty("user", required = true)
    val user: kotlin.String,
    @Schema(
        example = "null",
        description = "account for newapi AI usage; defaults to accountId if omitted",
    )
    @get:JsonProperty("newapiAccountId")
    val newapiAccountId: kotlin.Long? = null,
    @Schema(
        example = "null",
        description = "account for ccproxy AI usage; defaults to accountId if omitted",
    )
    @get:JsonProperty("ccproxyAccountId")
    val ccproxyAccountId: kotlin.Long? = null,
    @Schema(example = "null", description = "SSH public key to authorize for the user")
    @get:JsonProperty("sshPubkey")
    val sshPubkey: kotlin.String? = null,
    @Schema(
        example = "null",
        description = "(legacy, optional) a pre-created model key to bind; superseded by aiMode",
    )
    @get:JsonProperty("apiKeyId")
    val apiKeyId: kotlin.Long? = null,
    @Schema(
        example = "null",
        description =
            "How this machine's Claude Code reaches models, decided at create: newapi (default; a per-machine relay token, usable as soon as the machine runs), ccproxy (the subscription login starts as soon as the machine runs, a human login-operator completes the OAuth on ccproxy's side, and aiStatus lands ready when it does; 400 if ccproxy is not configured), or none. Without this a ccproxy machine had to be created on newapi and switched once running, which provisioned its AI channel twice.",
    )
    @get:JsonProperty("aiMode")
    val aiMode: kotlin.String? = null,
) {}
