/*
 *  Description: The AI-provisioning seam. An AiProvider knows how to wire one AiMode onto a machine:
 *               prepareInit contributes extra init-machine.py arguments (run during the machine's
 *               SSH init), onReady lands the terminal aiStatus, teardown releases backend resources
 *               on delete. The provisioner resolves the provider from the machine's aiMode via
 *               AiProviderRegistry, so adding a mode (e.g. ccproxy) is a new AiProvider, nothing
 *               else. AI setup is orthogonal to the machine's own lifecycle: a failure here marks
 *               aiStatus=ERROR but never blocks the machine from reaching RUNNING.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.machine.instance.Machine
import org.springframework.stereotype.Component

interface AiProvider {
    val mode: AiMode

    /**
     * Mint/record any backend resources for this machine and return a suffix to append to the
     * init-machine.py command (already shell-quoted), or "" if nothing to inject. May mutate the
     * machine (e.g. record a token id, set aiStatus).
     */
    fun prepareInit(machine: Machine): String

    /** Land the terminal aiStatus once the machine is RUNNING. */
    fun onReady(machine: Machine)

    /** Release backend resources when the machine is deleted (best-effort). */
    fun teardown(machine: Machine)
}

/** The no-AI provider: the machine gets no model access. */
@Component
class NoneAiProvider : AiProvider {
    override val mode = AiMode.NONE

    override fun prepareInit(machine: Machine): String = ""

    override fun onReady(machine: Machine) {
        machine.aiStatus = AiStatus.DISABLED
    }

    override fun teardown(machine: Machine) {}
}

/** Resolves the AiProvider for a machine's mode; unknown/absent modes fall back to NONE. */
@Component
class AiProviderRegistry(providers: List<AiProvider>) {
    private val byMode = providers.associateBy { it.mode }

    fun forMode(mode: AiMode?): AiProvider = byMode[mode] ?: byMode.getValue(AiMode.NONE)
}
