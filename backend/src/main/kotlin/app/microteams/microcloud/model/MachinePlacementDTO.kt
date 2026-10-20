package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * where the machine runs, for display and diagnosis: the form it was created as and the provider's
 * own names for its location. Informational only; nothing in the API takes it as input, and a
 * tenant still chooses only an offering.
 *
 * @param kind provider + machine form, e.g. proxmox/lxc or proxmox/vm
 * @param cluster name of the provider cluster or region
 * @param node host within the cluster
 */
data class MachinePlacementDTO(
    @Schema(
        example = "proxmox/vm",
        required = true,
        description = "provider + machine form, e.g. proxmox/lxc or proxmox/vm",
    )
    @get:JsonProperty("kind", required = true)
    val kind: kotlin.String,
    @Schema(
        example = "lab",
        required = true,
        description = "name of the provider cluster or region",
    )
    @get:JsonProperty("cluster", required = true)
    val cluster: kotlin.String,
    @Schema(example = "pve119", required = true, description = "host within the cluster")
    @get:JsonProperty("node", required = true)
    val node: kotlin.String,
) {}
