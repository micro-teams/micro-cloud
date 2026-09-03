package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.Valid

/**
 * @param id
 * @param machineId
 * @param at when the event was recorded
 * @param action
 * @param phase
 * @param level
 * @param message one sentence, at most 512 characters
 * @param detail the evidence: a UPID, a duration, an output tail, ccproxy's raw status, an
 *   exception
 */
data class MachineEventDTO(
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("id", required = true)
    val id: kotlin.Long,
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("machineId", required = true)
    val machineId: kotlin.Long,
    @Schema(example = "null", required = true, description = "when the event was recorded")
    @get:JsonProperty("at", required = true)
    val at: java.time.OffsetDateTime,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("action", required = true)
    val action: MachineEventActionDTO,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("phase", required = true)
    val phase: MachineEventPhaseDTO,
    @field:Valid
    @Schema(example = "null", required = true, description = "")
    @get:JsonProperty("level", required = true)
    val level: MachineEventLevelDTO,
    @Schema(example = "null", required = true, description = "one sentence, at most 512 characters")
    @get:JsonProperty("message", required = true)
    val message: kotlin.String,
    @Schema(
        example = "null",
        description =
            "the evidence: a UPID, a duration, an output tail, ccproxy's raw status, an exception",
    )
    @get:JsonProperty("detail")
    val detail: kotlin.String? = null,
) {}
