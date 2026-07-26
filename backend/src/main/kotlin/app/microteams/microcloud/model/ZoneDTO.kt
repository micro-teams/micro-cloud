package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * a locality partition of placements (disjoint, complete); same zone = faster comm
 *
 * @param id
 * @param name
 * @param status
 * @param description
 * @param placementIds
 * @param createdAt
 */
data class ZoneDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("name", required = true)
    val name: kotlin.String,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: ZoneStatusDTO,
    @Schema(example = "null", description = "")
    @get:JsonProperty("description")
    val description: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("placementIds")
    val placementIds: kotlin.collections.List<kotlin.Long>? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
