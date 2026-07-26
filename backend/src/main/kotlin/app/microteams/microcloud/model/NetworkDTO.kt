package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * an IP range bound to a placement; machines in that placement draw IPs from it
 *
 * @param id
 * @param placementId
 * @param startIp first usable IPv4 (inclusive)
 * @param endIp last usable IPv4 (inclusive)
 * @param gateway
 * @param prefixLength e.g. 20 for /20
 * @param bridge Proxmox bridge, e.g. vmbr0
 * @param status
 * @param name
 * @param totalCount total addresses (read-only)
 * @param allocatedCount allocated addresses (read-only)
 * @param createdAt
 */
data class NetworkDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("placementId", required = true)
    val placementId: kotlin.Long,
    @Schema(example = "null", required = true, description = "first usable IPv4 (inclusive)")
    @get:JsonProperty("startIp", required = true)
    val startIp: kotlin.String,
    @Schema(example = "null", required = true, description = "last usable IPv4 (inclusive)")
    @get:JsonProperty("endIp", required = true)
    val endIp: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("gateway", required = true)
    val gateway: kotlin.String,
    @Schema(example = "null", required = true, description = "e.g. 20 for /20")
    @get:JsonProperty("prefixLength", required = true)
    val prefixLength: kotlin.Int,
    @Schema(example = "null", required = true, description = "Proxmox bridge, e.g. vmbr0")
    @get:JsonProperty("bridge", required = true)
    val bridge: kotlin.String,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: NetworkStatusDTO,
    @Schema(example = "null", description = "")
    @get:JsonProperty("name")
    val name: kotlin.String? = null,
    @Schema(example = "null", description = "total addresses (read-only)")
    @get:JsonProperty("totalCount")
    val totalCount: kotlin.Int? = null,
    @Schema(example = "null", description = "allocated addresses (read-only)")
    @get:JsonProperty("allocatedCount")
    val allocatedCount: kotlin.Int? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
