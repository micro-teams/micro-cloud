package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * cursor-pagination metadata
 *
 * @param pageSize
 * @param hasPrev
 * @param hasMore
 * @param pageStart id of the first item on this page
 * @param prevStart cursor for the previous page
 * @param nextStart cursor for the next page; null if no more
 */
data class PageDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("pageSize", required = true)
    val pageSize: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("hasPrev", required = true)
    val hasPrev: kotlin.Boolean,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("hasMore", required = true)
    val hasMore: kotlin.Boolean,
    @Schema(example = "null", description = "id of the first item on this page")
    @get:JsonProperty("pageStart")
    val pageStart: kotlin.Long? = null,
    @Schema(example = "null", description = "cursor for the previous page")
    @get:JsonProperty("prevStart")
    val prevStart: kotlin.Long? = null,
    @Schema(example = "null", description = "cursor for the next page; null if no more")
    @get:JsonProperty("nextStart")
    val nextStart: kotlin.Long? = null,
) {}
