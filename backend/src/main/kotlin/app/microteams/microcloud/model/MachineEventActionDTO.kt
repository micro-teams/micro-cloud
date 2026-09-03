package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The lifecycle action an event belongs to. PROVISION: create on Proxmox through to running. START
 * / SHUTDOWN / STOP: the matching Proxmox task. DELETE: teardown (Proxmox, AI, IP). AI_SWITCH: a
 * switch of the machine's Claude Code between newapi and ccproxy. AI_LOGIN: a ccproxy subscription
 * login, from start to ready. Values: PROVISION,START,SHUTDOWN,STOP,DELETE,AI_SWITCH,AI_LOGIN
 */
enum class MachineEventActionDTO(@get:JsonValue val value: kotlin.String) {

    PROVISION("PROVISION"),
    START("START"),
    SHUTDOWN("SHUTDOWN"),
    STOP("STOP"),
    DELETE("DELETE"),
    AI_SWITCH("AI_SWITCH"),
    AI_LOGIN("AI_LOGIN");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): MachineEventActionDTO {
            return values().first { it -> it.value == value }
        }
    }
}
