package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * @param name
 * @param apiUrl
 * @param tokenId
 * @param tokenSecret the Proxmox API token secret (write-only)
 * @param verifyTls
 */
data class CreateProxmoxClusterRequestDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("name", required = true)
    val name: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("apiUrl", required = true)
    val apiUrl: kotlin.String,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("tokenId", required = true)
    val tokenId: kotlin.String,
    @Schema(
        example = "null",
        required = true,
        description = "the Proxmox API token secret (write-only)",
    )
    @get:JsonProperty("tokenSecret", required = true)
    val tokenSecret: kotlin.String,
    @Schema(example = "null", description = "")
    @get:JsonProperty("verifyTls")
    val verifyTls: kotlin.Boolean? = true,
) {}
