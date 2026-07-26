package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * returned once on creation — the only time the plaintext secret is exposed
 *
 * @param id
 * @param tenantId
 * @param secret plaintext auth secret; store it now, not retrievable later
 * @param label
 * @param createdAt
 */
data class CreateTenantSecretResponseDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("tenantId", required = true)
    val tenantId: kotlin.Long,
    @Schema(
        example = "null",
        required = true,
        description = "plaintext auth secret; store it now, not retrievable later",
    )
    @get:JsonProperty("secret", required = true)
    val secret: kotlin.String,
    @Schema(example = "null", description = "")
    @get:JsonProperty("label")
    val label: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
