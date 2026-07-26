package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param externalRef */
data class CreateCustomerRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("externalRef", required = true)
    val externalRef: kotlin.String
) {}
