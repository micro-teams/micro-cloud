/*
 *  Description: The AiProvider for a machine created with aiMode=ccproxy. Nothing to inject into
 *               init: ccproxy edits the machine's ~/.claude/settings.json itself once the machine is
 *               registered with it (the provisioner's birth-init). onReady starts the subscription
 *               login, so the machine goes from provisioning straight to a login the operator
 *               completes — instead of being set up on newapi first and switched afterwards.
 *               Teardown is handled by the provisioner for every mode (a machine holds its ccproxy
 *               registration whatever its mode).
 *
 *  Author(s):
 *      Zhifei Li    <andylizf@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.machine.instance.Machine
import org.springframework.stereotype.Component

@Component
class CcproxyAiProvider(private val switchService: CcproxySwitchService) : AiProvider {
    override val mode = AiMode.CCPROXY

    override fun prepareInit(machine: Machine): String = ""

    override fun onReady(machine: Machine) {
        switchService.beginLogin(machine)
    }

    override fun teardown(machine: Machine) {}
}
