package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param node
 * @param storage
 * @param content
 */
data class ProxmoxInventoryStoragesInnerDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("node")
    val node: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("storage")
    val storage: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("content")
    val content: kotlin.String? = null,
) {}
