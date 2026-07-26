package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param customerId
 * @param name
 */
data class CreateAccountRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("customerId", required = true)
    val customerId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("name", required = true)
    val name: kotlin.String,
) {}
