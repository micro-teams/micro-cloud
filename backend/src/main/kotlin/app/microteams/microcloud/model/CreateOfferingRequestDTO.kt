package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param tenantId
 * @param machineTypeId
 * @param zoneId
 * @param templateId
 */
data class CreateOfferingRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("tenantId", required = true)
    val tenantId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("machineTypeId", required = true)
    val machineTypeId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("zoneId", required = true)
    val zoneId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("templateId", required = true)
    val templateId: kotlin.Long,
) {}
