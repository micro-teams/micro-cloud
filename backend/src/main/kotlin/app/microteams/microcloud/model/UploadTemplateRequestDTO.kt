package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/** @param placementId */
data class UploadTemplateRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("placementId", required = true)
    val placementId: kotlin.Long
) {}
