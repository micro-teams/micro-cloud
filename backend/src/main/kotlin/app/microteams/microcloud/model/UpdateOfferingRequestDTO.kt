package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * partial update; omitted fields unchanged
 *
 * @param status
 */
data class UpdateOfferingRequestDTO(
    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("status")
    val status: OfferingStatusDTO? = null
) {}
