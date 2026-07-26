package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Values: active,suspended */
enum class CustomerStatusDTO(@get:JsonValue val value: kotlin.String) {

    active("active"),
    suspended("suspended");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): CustomerStatusDTO {
            return values().first { it -> it.value == value }
        }
    }
}
