package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param kind provider + machine form this placement hosts, e.g. proxmox/lxc or proxmox/vm
 * @param name
 * @param clusterId
 * @param node
 * @param pool
 * @param storage
 */
data class CreatePlacementRequestDTO(
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
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("storage", required = true)
    val storage: kotlin.String,
) {}
