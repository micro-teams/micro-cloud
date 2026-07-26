package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * a Proxmox provider credential (the token secret is write-only, never returned)
 *
 * @param id
 * @param name
 * @param apiUrl e.g. https://pve.example:8006
 * @param tokenId e.g. user@pve!tokenname
 * @param status
 * @param verifyTls
 * @param createdAt
 */
data class ProxmoxClusterDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("name", required = true)
    val name: kotlin.String,
    @Schema(example = "null", required = true, description = "e.g. https://pve.example:8006")
    @get:JsonProperty("apiUrl", required = true)
    val apiUrl: kotlin.String,
    @Schema(example = "null", required = true, description = "e.g. user@pve!tokenname")
    @get:JsonProperty("tokenId", required = true)
    val tokenId: kotlin.String,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("status", required = true)
    val status: ProxmoxClusterStatusDTO,
    @Schema(example = "null", description = "")
    @get:JsonProperty("verifyTls")
    val verifyTls: kotlin.Boolean? = null,
    @Schema(example = "null", description = "")
    @get:JsonProperty("createdAt")
    val createdAt: java.time.OffsetDateTime? = null,
) {}
