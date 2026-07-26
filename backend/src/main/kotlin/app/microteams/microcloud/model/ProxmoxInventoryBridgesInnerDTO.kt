package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param node
 * @param bridge
 * @param cidr
 */
data class ProxmoxInventoryBridgesInnerDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("node")
    val node: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("bridge")
    val bridge: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("cidr")
    val cidr: kotlin.String? = null,
) {}
