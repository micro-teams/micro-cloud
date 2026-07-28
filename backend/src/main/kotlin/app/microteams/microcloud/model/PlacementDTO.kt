package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * a concrete landing coordinate machines can be created on. Its leading coordinate is the `kind` —
 * the provider + machine form it hosts (proxmox/lxc, proxmox/vm, …) — followed by the
 * provider-specific location (cluster+node+pool+storage for Proxmox). A machine can only be created
 * here from a template of the same kind.
 *
 * @param id
 * @param kind provider + machine form this placement hosts, e.g. proxmox/lxc or proxmox/vm
 * @param name
 * @param clusterId
 * @param node
 * @param pool
 * @param storage storage for the machine rootfs / disk, e.g. local-lvm
 * @param status
 * @param createdAt
 */
data class PlacementDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(
        example = "proxmox/lxc",
        required = true,
        description = "provider + machine form this placement hosts, e.g. proxmox/lxc or proxmox/vm",
    )
    @get:JsonProperty("kind", required = true)
    val kind: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("name", required = true)
    val name: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("clusterId", required = true)
    val clusterId: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("node", required = true)
    val node: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("pool", required = true)
    val pool: kotlin.String,
    @Schema(
        example = "null",
        required = true,
        description = "storage for the machine rootfs / disk, e.g. local-lvm",
    )
    @get:JsonProperty("storage", required = true)
    val storage: kotlin.String,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: PlacementStatusDTO,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
