package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Values: active,disabled */
enum class MachineTemplateStatusDTO(@get:JsonValue val value: kotlin.String) {

    active("active"),
    disabled("disabled");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): MachineTemplateStatusDTO {
            return values().first { it -> it.value == value }
        }
    }
}
