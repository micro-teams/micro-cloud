package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param name
 * @param placementIds
 * @param description
 */
data class CreateZoneRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("name", required = true)
    val name: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("placementIds", required = true)
    val placementIds: kotlin.collections.List<kotlin.Long>,
    @Schema(example = "null", description = "")
    @get:JsonProperty("description")
    val description: kotlin.String? = null,
) {}
