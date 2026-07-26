package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Values: provisioning,starting,running,stopping,stopped,deleting,deleted,error */
enum class MachineStatusDTO(@get:JsonValue val value: kotlin.String) {

    provisioning("provisioning"),
    starting("starting"),
    running("running"),
    stopping("stopping"),
    stopped("stopped"),
    deleting("deleting"),
    deleted("deleted"),
    error("error");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): MachineStatusDTO {
            return values().first { it -> it.value == value }
        }
    }
}
