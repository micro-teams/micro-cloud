package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param id
 * @param externalRef upstream user reference (cheese/microteams user)
 * @param status
 * @param createdAt
 */
data class CustomerDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(
        example = "null",
        required = true,
        description = "upstream user reference (cheese/microteams user)",
    )
    @get:JsonProperty("externalRef", required = true)
    val externalRef: kotlin.String,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: CustomerStatusDTO,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
