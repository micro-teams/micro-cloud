package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * live read from the cluster to help pick placement coordinates
 *
 * @param nodes
 * @param pools
 * @param storages
 * @param bridges
 */
data class ProxmoxInventoryDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("nodes", required = true)
    val nodes: kotlin.collections.List<kotlin.String>,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("pools", required = true)
    val pools: kotlin.collections.List<kotlin.String>,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("storages", required = true)
    val storages: kotlin.collections.List<ProxmoxInventoryStoragesInnerDTO>,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("bridges", required = true)
    val bridges: kotlin.collections.List<ProxmoxInventoryBridgesInnerDTO>,
) {}
