package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param placementId
 * @param startIp
 * @param endIp
 * @param gateway
 * @param prefixLength
 * @param bridge
 * @param name
 */
data class CreateNetworkRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("placementId", required = true)
    val placementId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("startIp", required = true)
    val startIp: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("endIp", required = true)
    val endIp: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("gateway", required = true)
    val gateway: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("prefixLength", required = true)
    val prefixLength: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("bridge", required = true)
    val bridge: kotlin.String,
    @Schema(example = "null", description = "")
    @get:JsonProperty("name")
    val name: kotlin.String? = null,
) {}
