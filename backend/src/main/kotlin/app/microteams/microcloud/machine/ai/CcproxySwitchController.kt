/*
 *  Description: Super-admin endpoints to switch one machine's Claude Code between the newapi relay
 *               and a ccproxy subscription login. Hand-written (not in the generated MachineApi):
 *               these are internal operator actions, not part of the tenant contract. Authorization
 *               is the same @Guard mechanism the generated controllers use.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.machine.instance.Machine
import org.rucca.cheese.auth.annotation.Guard
import org.rucca.cheese.common.persistent.IdType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** What the switch endpoints return: the machine's AI mode/status after the action. */
data class MachineAiStatusResponse(
    val machineId: IdType,
    val aiMode: String?,
    val aiStatus: String?,
    val ccproxyMachineId: Long?,
)

@RestController
class CcproxySwitchController(private val switchService: CcproxySwitchService) {

    @Guard("switch-machine-ccproxy", "machine")
    @PostMapping("/machine/{id}/ai/ccproxy")
    fun switchToCcproxy(@PathVariable("id") id: IdType): ResponseEntity<MachineAiStatusResponse> =
        ResponseEntity.accepted().body(switchService.switchToCcproxy(id).toAiStatus())

    @Guard("switch-machine-newapi", "machine")
    @PostMapping("/machine/{id}/ai/newapi")
    fun switchToNewapi(@PathVariable("id") id: IdType): ResponseEntity<MachineAiStatusResponse> =
        ResponseEntity.ok(switchService.switchToNewapi(id).toAiStatus())

    private fun Machine.toAiStatus() =
        MachineAiStatusResponse(
            machineId = this.id!!,
            aiMode = this.aiMode?.name,
            aiStatus = this.aiStatus?.name,
            ccproxyMachineId = this.ccproxyMachineId,
        )
}
