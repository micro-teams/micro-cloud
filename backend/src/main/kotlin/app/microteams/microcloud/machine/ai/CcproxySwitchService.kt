/*
 *  Description: The super-admin-triggered switch of a machine's Claude Code between the newapi relay
 *               and a real subscription login behind ccproxy. ccproxy owns every on-machine edit: at
 *               birth it merged an HTTPS_PROXY→engine into ~/.claude/settings.json; on switch-to-
 *               ccproxy it drives the OAuth (a human login-operator completes it on ccproxy's side)
 *               and, on success, removes the newapi keys so the next Claude runs official-through-
 *               engine. MicroCloud only triggers + polls that, and — on the way BACK — restores the
 *               newapi keys (ccproxy never does) and frees the ccproxy account. The proxy line stays
 *               in settings.json throughout (unregistered → the engine tunnels it straight through),
 *               so newapi keeps working. Switching never blocks a running Claude: env is read once at
 *               start, so the change lands on the next Claude the user starts.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.common.config.MicroCloudConfig
import app.microteams.microcloud.machine.instance.Machine
import app.microteams.microcloud.machine.instance.MachineRepository
import org.rucca.cheese.common.error.BadRequestError
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CcproxySwitchService(
    private val machineRepository: MachineRepository,
    private val ccproxyClient: CcproxyClient,
    private val newapiClient: NewapiClient,
    private val settingsSsh: MachineSettingsSsh,
    private val loginPoller: CcproxyLoginPoller,
    private val config: MicroCloudConfig,
) {
    private val log = LoggerFactory.getLogger(CcproxySwitchService::class.java)

    private fun get(id: IdType): Machine =
        machineRepository.findById(id).orElseThrow { NotFoundError("machine", id) }

    /**
     * Switch a machine to the ccproxy subscription login. Ensures it is registered with ccproxy
     * (birth-init may have been skipped/failed), starts its login (a human completes the OAuth on
     * ccproxy's side), and polls to READY in the background. ccproxy flips settings.json to
     * official itself on success — MicroCloud writes nothing on the machine here.
     */
    @Transactional
    fun switchToCcproxy(id: IdType): Machine {
        val machine = get(id)
        if (!ccproxyClient.isConfigured()) throw BadRequestError("ccproxy is not configured")
        if (machine.ip.isNullOrBlank() || machine.loginUser.isNullOrBlank())
            throw BadRequestError("machine ${machine.id} has no reachable login target yet")

        // Register on ccproxy if not already (this also (re)merges the engine proxy into
        // settings.json); wait until it is provisioned enough to log in.
        if (machine.ccproxyMachineId == null) {
            val m =
                ccproxyClient.createMachine(machine.ip!!, machine.loginUser!!, 22, machine.hostname)
            machine.ccproxyMachineId = m.id
            machineRepository.save(machine)
        }
        beginLogin(machine)
        return machine
    }

    /**
     * Start the subscription login on a machine already registered with ccproxy, and poll it to
     * READY in the background. Shared by the super-admin switch and by a machine created with
     * aiMode=ccproxy, whose provisioner calls this the moment the machine runs.
     */
    fun beginLogin(machine: Machine) {
        val ccId =
            machine.ccproxyMachineId
                ?: throw BadRequestError("machine ${machine.id} is not registered with ccproxy")
        // A previous login the operator never completed leaves the machine stuck in `loggingIn`;
        // cancel it so this (re)switch can start a fresh login instead of 409-ing.
        cancelActiveLogin(ccId)
        awaitCcproxyStatus(ccId, setOf("awaitingLogin", "ready"))

        ccproxyClient.startLogin(ccId) // 409 if a login is already in progress → surfaced as 400
        machine.aiMode = AiMode.CCPROXY
        machine.aiStatus = AiStatus.PROVISIONING
        machineRepository.save(machine)

        loginPoller.pollLoginToReady(machine.id!!, ccId)
    }

    /**
     * Switch a machine back to newapi: restore the ANTHROPIC_* keys ccproxy removed (ccproxy never
     * restores them) and free the ccproxy account by tearing its machine down. The proxy line stays
     * in settings.json — now an unregistered session the engine tunnels through — so newapi works.
     */
    @Transactional
    fun switchToNewapi(id: IdType): Machine {
        val machine = get(id)
        val base =
            config.newapi.machineBaseUrl?.takeIf { it.isNotBlank() }
                ?: throw BadRequestError("newapi machine-base-url is not configured")
        if (!newapiClient.isConfigured()) throw BadRequestError("newapi is not configured")

        // Reuse this machine's newapi token if it still has one, else mint a fresh one.
        val tokenId =
            machine.newapiTokenId
                ?: newapiClient.ensureToken("mc-machine-${machine.id}", config.newapi.defaultQuota)
        machine.newapiTokenId = tokenId
        val key = newapiClient.revealKey(tokenId)
        settingsSsh.restoreNewapiEnv(machine, base, key, config.provisioning.taskTimeoutSeconds)

        // Free the ccproxy account (removes the engine session too); the proxy line remains in
        // settings.json as an unregistered passthrough. A future switch re-registers. Cancel any
        // in-flight login first so a half-done login doesn't linger.
        machine.ccproxyMachineId?.let { ccId ->
            runCatching { cancelActiveLogin(ccId) }
            runCatching { ccproxyClient.deleteMachine(ccId) }
                .onFailure {
                    log.warn("ccproxy delete for machine {} failed: {}", machine.id, it.message)
                }
        }
        machine.ccproxyMachineId = null
        machine.aiMode = AiMode.NEWAPI
        machine.aiStatus = AiStatus.READY
        machineRepository.save(machine)
        return machine
    }

    /** Cancel the machine's current login-request if one is in progress (best-effort). */
    private fun cancelActiveLogin(ccId: Long) {
        val m = runCatching { ccproxyClient.getMachine(ccId) }.getOrNull() ?: return
        m.currentLoginRequestId?.let { lrId ->
            runCatching { ccproxyClient.cancelLogin(lrId) }
                .onFailure {
                    log.warn("cancel login {} on ccproxy {} failed: {}", lrId, ccId, it.message)
                }
        }
    }

    /** Block briefly until the ccproxy machine reaches one of [wanted] (or errors/times out). */
    private fun awaitCcproxyStatus(ccId: Long, wanted: Set<String>) {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val m = ccproxyClient.getMachine(ccId)
            if (m.status in wanted) return
            if (m.status == "error")
                throw BadRequestError("ccproxy machine $ccId errored: ${m.error}")
            Thread.sleep(3000)
        }
        throw BadRequestError("ccproxy machine $ccId did not become ready to log in")
    }
}
