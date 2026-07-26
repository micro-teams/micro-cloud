package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * a (machine type, zone, template) triple a tenant may use; carries the type spec ranges so the
 * tenant needs no other lookups
 *
 * @param id
 * @param tenantId
 * @param status
 * @param machineTypeId
 * @param machineTypeName
 * @param coresMin
 * @param coresMax
 * @param memoryMbMin
 * @param memoryMbMax
 * @param diskGbMin
 * @param diskGbMax
 * @param zoneId
 * @param zoneName
 * @param templateId
 * @param templateName
 * @param createdAt
 */
data class OfferingDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("tenantId", required = true)
    val tenantId: kotlin.Long,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: OfferingStatusDTO,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("machineTypeId", required = true)
    val machineTypeId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("machineTypeName", required = true)
    val machineTypeName: kotlin.String,
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
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("zoneId", required = true)
    val zoneId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("zoneName", required = true)
    val zoneName: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("templateId", required = true)
    val templateId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("templateName", required = true)
    val templateName: kotlin.String,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
