package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * partial update; omitted fields unchanged
 *
 * @param name
 * @param storage
 * @param status
 */
data class UpdatePlacementRequestDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("name")
    val name: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("storage")
    val storage: kotlin.String? = null,
    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("status")
    val status: PlacementStatusDTO? = null,
) {}
