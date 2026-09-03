package app.microteams.microcloud.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The step within the action. STARTED: the action began (detail: where and with what).
 * PVE_TASK_SUBMITTED: a Proxmox task was submitted (detail: its UPID). PVE_TASK_DONE: that task
 * finished OK (detail: UPID + duration). SSH_REACHABLE: the guest accepts TCP :22 (detail: how long
 * that took). INIT_DONE: init-machine finished (detail: its output tail). CCPROXY_REGISTERED: the
 * birth registration with ccproxy, INFO when it succeeded and WARN when it failed (the machine
 * keeps running). AI_SETUP_FAILED: the AI channel could not be set up, aiStatus is error, the
 * machine is unaffected. RUNNING: the machine reached running (the end of PROVISION).
 * LOGIN_STARTED: ccproxy started the login (detail: its login request id and the account).
 * LOGIN_POLLED: ccproxy's reported status changed (detail: the raw status). LOGIN_READY: the login
 * completed and the machine holds a credential. LOGIN_CANCELLED: a previous, never-completed login
 * was cancelled so a fresh one could start. DONE: the action completed (START / SHUTDOWN / STOP /
 * DELETE / AI_SWITCH). FAILED: the action failed (detail: the exception). Values:
 * STARTED,PVE_TASK_SUBMITTED,PVE_TASK_DONE,SSH_REACHABLE,INIT_DONE,CCPROXY_REGISTERED,AI_SETUP_FAILED,RUNNING,LOGIN_STARTED,LOGIN_POLLED,LOGIN_READY,LOGIN_CANCELLED,DONE,FAILED
 */
enum class MachineEventPhaseDTO(@get:JsonValue val value: kotlin.String) {

    STARTED("STARTED"),
    PVE_TASK_SUBMITTED("PVE_TASK_SUBMITTED"),
    PVE_TASK_DONE("PVE_TASK_DONE"),
    SSH_REACHABLE("SSH_REACHABLE"),
    INIT_DONE("INIT_DONE"),
    CCPROXY_REGISTERED("CCPROXY_REGISTERED"),
    AI_SETUP_FAILED("AI_SETUP_FAILED"),
    RUNNING("RUNNING"),
    LOGIN_STARTED("LOGIN_STARTED"),
    LOGIN_POLLED("LOGIN_POLLED"),
    LOGIN_READY("LOGIN_READY"),
    LOGIN_CANCELLED("LOGIN_CANCELLED"),
    DONE("DONE"),
    FAILED("FAILED");

    companion object {
        @JvmStatic
        @JsonCreator
        fun forValue(value: kotlin.String): MachineEventPhaseDTO {
            return values().first { it -> it.value == value }
        }
    }
}
