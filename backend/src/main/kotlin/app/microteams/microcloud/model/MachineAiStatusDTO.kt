package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema

/**
 * A machine's AI mode/status after a switch action.
 *
 * @param machineId
 * @param aiMode NONE | NEWAPI | CCPROXY
 * @param aiStatus DISABLED | PROVISIONING | READY | ERROR
 * @param ccproxyMachineId this machine's id on ccproxy
 */
data class MachineAiStatusDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("machineId", required = true)
    val machineId: kotlin.Long,
    @Schema(example = "null", description = "NONE | NEWAPI | CCPROXY")
    @get:JsonProperty("aiMode")
    val aiMode: kotlin.String? = null,
    @Schema(example = "null", description = "DISABLED | PROVISIONING | READY | ERROR")
    @get:JsonProperty("aiStatus")
    val aiStatus: kotlin.String? = null,
    @Schema(example = "null", description = "this machine's id on ccproxy")
    @get:JsonProperty("ccproxyMachineId")
    val ccproxyMachineId: kotlin.Long? = null,
) {}
