package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param id
 * @param name stable identifier, e.g. debian13
 * @param kind the image's provider + machine form, e.g. proxmox/lxc or proxmox/vm; usable only on a
 *   placement of the same kind
 * @param status
 * @param description
 * @param createdAt
 */
data class MachineTemplateDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "stable identifier, e.g. debian13")
    @get:JsonProperty("name", required = true)
    val name: kotlin.String,
    @Schema(
        example = "proxmox/lxc",
        required = true,
        description =
            "the image's provider + machine form, e.g. proxmox/lxc or proxmox/vm; usable only on a placement of the same kind",
    )
    @get:JsonProperty("kind", required = true)
    val kind: kotlin.String,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: MachineTemplateStatusDTO,
    @Schema(example = "null", description = "")
    @get:JsonProperty("description")
    val description: kotlin.String? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
