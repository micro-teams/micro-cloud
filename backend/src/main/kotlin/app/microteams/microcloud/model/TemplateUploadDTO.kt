package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * state of a template's image on one placement's storage
 *
 * @param id
 * @param templateId
 * @param placementId
 * @param status
 * @param volid Proxmox volume id once uploaded
 * @param jobLog upload/import job log
 * @param updatedAt
 */
data class TemplateUploadDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("templateId", required = true)
    val templateId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("placementId", required = true)
    val placementId: kotlin.Long,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: TemplateUploadStatusDTO,
    @Schema(example = "null", description = "Proxmox volume id once uploaded")
    @get:JsonProperty("volid")
    val volid: kotlin.String? = null,
    @Schema(example = "null", description = "upload/import job log")
    @get:JsonProperty("jobLog")
    val jobLog: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("updatedAt")
    val updatedAt: java.time.OffsetDateTime? = null,
) {}
