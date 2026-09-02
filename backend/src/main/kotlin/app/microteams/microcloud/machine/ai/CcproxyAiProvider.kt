/*
 *  Description: The AiProvider for a machine created with aiMode=ccproxy. Nothing to inject into
 *               init: ccproxy edits the machine's ~/.claude/settings.json itself once the machine is
 *               registered with it (the provisioner's birth-init). onReady only marks the channel as
 *               provisioning and asks for the login to be started once the provisioner's transaction
 *               has committed — the start blocks on ccproxy and its poller writes aiStatus from
 *               another thread, so neither may run inside that transaction. Teardown is handled by
 *               the provisioner for every mode (a machine holds its ccproxy registration whatever
 *               its mode).
 *
 *  Author(s):
 *      Zhifei Li    <andylizf@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.machine.instance.Machine
import org.rucca.cheese.common.error.BadRequestError
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class CcproxyAiProvider(private val switchService: CcproxySwitchService) : AiProvider {
    override val mode = AiMode.CCPROXY

    override fun prepareInit(machine: Machine): String = ""

    override fun onReady(machine: Machine) {
        if (machine.ccproxyMachineId == null)
            throw BadRequestError("machine ${machine.id} is not registered with ccproxy")
        machine.aiStatus = AiStatus.PROVISIONING
        val id = machine.id!!
        val start = { switchService.beginLoginAfterProvision(id) }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = start()
                }
            )
        } else {
            start()
        }
    }

    override fun teardown(machine: Machine) {}
}
