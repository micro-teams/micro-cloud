package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * a performance class; backed by one or more placements (super-admin detail)
 *
 * @param id
 * @param name
 * @param coresMin
 * @param coresMax
 * @param memoryMbMin
 * @param memoryMbMax
 * @param diskGbMin
 * @param diskGbMax
 * @param status
 * @param description
 * @param placementIds placements that back this type (admin config)
 * @param createdAt
 */
data class MachineTypeDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("name", required = true)
    val name: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("coresMin", required = true)
    val coresMin: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("coresMax", required = true)
    val coresMax: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("memoryMbMin", required = true)
    val memoryMbMin: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("memoryMbMax", required = true)
    val memoryMbMax: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("diskGbMin", required = true)
    val diskGbMin: kotlin.Int,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("diskGbMax", required = true)
    val diskGbMax: kotlin.Int,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: MachineTypeStatusDTO,
    @Schema(example = "null", description = "")
    @get:JsonProperty("description")
    val description: kotlin.String? = null,
    @Schema(example = "null", description = "placements that back this type (admin config)")
    @get:JsonProperty("placementIds")
    val placementIds: kotlin.collections.List<kotlin.Long>? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
