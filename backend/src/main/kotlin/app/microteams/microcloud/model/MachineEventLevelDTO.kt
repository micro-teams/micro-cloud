package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Values: INFO,WARN,ERROR */
enum class MachineEventLevelDTO(@get:JsonValue val value: kotlin.String) {

    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): MachineEventLevelDTO {
            return values().first { it -> it.value == value }
        }
    }
}
