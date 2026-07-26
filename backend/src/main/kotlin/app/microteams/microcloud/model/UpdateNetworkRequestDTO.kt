package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * partial update; omitted fields unchanged
 *
 * @param name
 * @param gateway
 * @param prefixLength
 * @param bridge
 * @param status
 */
data class UpdateNetworkRequestDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("name")
    val name: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("gateway")
    val gateway: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("prefixLength")
    val prefixLength: kotlin.Int? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("bridge")
    val bridge: kotlin.String? = null,
    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("status")
    val status: NetworkStatusDTO? = null,
) {}
