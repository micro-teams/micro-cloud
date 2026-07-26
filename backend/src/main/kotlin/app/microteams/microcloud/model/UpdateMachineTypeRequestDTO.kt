package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * partial update; omitted fields unchanged
 *
 * @param name
 * @param description
 * @param placementIds
 * @param coresMin
 * @param coresMax
 * @param memoryMbMin
 * @param memoryMbMax
 * @param diskGbMin
 * @param diskGbMax
 * @param status
 */
data class UpdateMachineTypeRequestDTO(
    @Schema(example = "null", description = "")
    @get:JsonProperty("name")
    val name: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("description")
    val description: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("placementIds")
    val placementIds: kotlin.collections.List<kotlin.Long>? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("coresMin")
    val coresMin: kotlin.Int? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("coresMax")
    val coresMax: kotlin.Int? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("memoryMbMin")
    val memoryMbMin: kotlin.Int? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("memoryMbMax")
    val memoryMbMax: kotlin.Int? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("diskGbMin")
    val diskGbMin: kotlin.Int? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("diskGbMax")
    val diskGbMax: kotlin.Int? = null,
    @field:Valid
    @Schema(example = "null", description = "")
    @get:JsonProperty("status")
    val status: MachineTypeStatusDTO? = null,
) {}
