package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Values: active,revoked */
enum class TenantSecretStatusDTO(@get:JsonValue val value: kotlin.String) {

    active("active"),
    revoked("revoked");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): TenantSecretStatusDTO {
            return values().first { it -> it.value == value }
        }
    }
}
