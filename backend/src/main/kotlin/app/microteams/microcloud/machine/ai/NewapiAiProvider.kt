/*
 *  Description: The newapi AiProvider. On prepareInit it mints (or reuses) a per-machine relay token
 *               in newapi, records the token id on the machine, and returns the init-machine.py args
 *               that point the machine's Claude Code at the newapi relay
 *               (ANTHROPIC_BASE_URL + ANTHROPIC_AUTH_TOKEN — API-key style, no OAuth). Fully
 *               automated: the machine is READY as soon as init runs. teardown deletes the token.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.common.config.MicroCloudConfig
import app.microteams.microcloud.machine.instance.Machine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class NewapiAiProvider(
    private val newapiClient: NewapiClient,
    private val config: MicroCloudConfig,
) : AiProvider {
    private val log = LoggerFactory.getLogger(NewapiAiProvider::class.java)

    override val mode = AiMode.NEWAPI

    override fun prepareInit(machine: Machine): String {
        // newapi not wired (no root password / no machine-facing URL) → the machine simply gets no
        // AI. Not an error: it just isn't set up.
        val base = config.newapi.machineBaseUrl?.takeIf { it.isNotBlank() }
        if (!newapiClient.isConfigured() || base == null) {
            log.info("newapi is not configured; machine {} gets no AI", machine.id)
            machine.aiStatus = AiStatus.DISABLED
            return ""
        }
        val tokenId =
            newapiClient.ensureToken("mc-machine-${machine.id}", config.newapi.defaultQuota)
        machine.newapiTokenId = tokenId
        val key = newapiClient.revealKey(tokenId)
        machine.aiStatus = AiStatus.PROVISIONING
        log.info("newapi token {} minted for machine {}", tokenId, machine.id)
        // init-machine.py writes these into the login user's shell + installs Claude Code.
        return " --anthropic-base-url '$base' --anthropic-token '$key'"
    }

    override fun onReady(machine: Machine) {
        // Only the machines we actually configured (PROVISIONING) become READY; DISABLED (newapi
        // not wired) stays DISABLED.
        if (machine.aiStatus == AiStatus.PROVISIONING) machine.aiStatus = AiStatus.READY
    }

    override fun teardown(machine: Machine) {
        machine.newapiTokenId?.let { runCatching { newapiClient.deleteToken(it) } }
    }
}
