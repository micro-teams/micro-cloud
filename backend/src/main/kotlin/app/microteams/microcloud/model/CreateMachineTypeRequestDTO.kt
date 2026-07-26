package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param name
 * @param placementIds
 * @param coresMin
 * @param coresMax
 * @param memoryMbMin
 * @param memoryMbMax
 * @param diskGbMin
 * @param diskGbMax
 * @param description
 */
data class CreateMachineTypeRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("name", required = true)
    val name: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("placementIds", required = true)
    val placementIds: kotlin.collections.List<kotlin.Long>,
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
    @Schema(example = "null", description = "")
    @get:JsonProperty("description")
    val description: kotlin.String? = null,
) {}
