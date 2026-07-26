package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * partial update; omitted fields unchanged
 *
 * @param name
 * @param apiUrl
 * @param tokenId
 * @param tokenSecret
 * @param verifyTls
 * @param status
 */
data class UpdateProxmoxClusterRequestDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("name")
    val name: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("apiUrl")
    val apiUrl: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("tokenId")
    val tokenId: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("tokenSecret")
    val tokenSecret: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("verifyTls")
    val verifyTls: kotlin.Boolean? = null,
    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("status")
    val status: ProxmoxClusterStatusDTO? = null,
) {}
