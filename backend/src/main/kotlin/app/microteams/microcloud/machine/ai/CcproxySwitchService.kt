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
import app.microteams.microcloud.machine.instance.MachineEventAction.AI_LOGIN
import app.microteams.microcloud.machine.instance.MachineEventAction.AI_SWITCH
import app.microteams.microcloud.machine.instance.MachineEventLevel.ERROR
import app.microteams.microcloud.machine.instance.MachineEventLevel.WARN
import app.microteams.microcloud.machine.instance.MachineEventPhase.DONE
import app.microteams.microcloud.machine.instance.MachineEventPhase.FAILED
import app.microteams.microcloud.machine.instance.MachineEventPhase.LOGIN_CANCELLED
import app.microteams.microcloud.machine.instance.MachineEventPhase.LOGIN_STARTED
import app.microteams.microcloud.machine.instance.MachineEventPhase.STARTED
import app.microteams.microcloud.machine.instance.MachineEventRecorder
import app.microteams.microcloud.machine.instance.MachineRepository
import org.rucca.cheese.common.error.BadRequestError
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
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
    private val events: MachineEventRecorder,
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
        events.record(machine, AI_SWITCH, STARTED, "switch to ccproxy requested")
        try {
            if (!ccproxyClient.isConfigured()) throw BadRequestError("ccproxy is not configured")
            if (machine.ip.isNullOrBlank() || machine.loginUser.isNullOrBlank())
                throw BadRequestError("machine ${machine.id} has no reachable login target yet")

            // Register on ccproxy if not already (this also (re)merges the engine proxy into
            // settings.json); wait until it is provisioned enough to log in.
            if (machine.ccproxyMachineId == null) {
                val m =
                    ccproxyClient.createMachine(
                        machine.ip!!,
                        machine.loginUser!!,
                        22,
                        machine.hostname,
                    )
                machine.ccproxyMachineId = m.id
                machineRepository.save(machine)
            }
            beginLogin(machine)
        } catch (e: Exception) {
            events.record(
                machine,
                AI_SWITCH,
                FAILED,
                "switch to ccproxy failed: ${e.message}",
                ERROR,
                cause = e,
            )
            throw e
        }
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
        cancelActiveLogin(machine, ccId)
        awaitCcproxyStatus(ccId, setOf("awaitingLogin", "ready"))

        // 409 if a login is already in progress → surfaced as 400
        val login = ccproxyClient.startLogin(ccId)
        machine.aiMode = AiMode.CCPROXY
        machine.aiStatus = AiStatus.PROVISIONING
        machineRepository.save(machine)
        events.record(
            machine,
            AI_LOGIN,
            LOGIN_STARTED,
            "ccproxy login started as request ${login.id}; a login-operator completes the OAuth",
            detail =
                "ccproxyMachineId=$ccId\nloginRequestId=${login.id}\nstatus=${login.status}\n" +
                    "accountEmail=${login.accountEmail}",
        )

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
        events.record(machine, AI_SWITCH, STARTED, "switch to newapi requested")
        try {
            val base =
                config.newapi.machineBaseUrl?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestError("newapi machine-base-url is not configured")
            if (!newapiClient.isConfigured()) throw BadRequestError("newapi is not configured")

            // Reuse this machine's newapi token if it still has one, else mint a fresh one.
            val tokenId =
                machine.newapiTokenId
                    ?: newapiClient.ensureToken(
                        "mc-machine-${machine.id}",
                        config.newapi.defaultQuota,
                    )
            machine.newapiTokenId = tokenId
            val key = newapiClient.revealKey(tokenId)
            settingsSsh.restoreNewapiEnv(machine, base, key, config.provisioning.taskTimeoutSeconds)

            // Free the ccproxy account (removes the engine session too); the proxy line remains in
            // settings.json as an unregistered passthrough. A future switch re-registers. Cancel
            // any in-flight login first so a half-done login doesn't linger.
            machine.ccproxyMachineId?.let { ccId ->
                runCatching { cancelActiveLogin(machine, ccId) }
                runCatching { ccproxyClient.deleteMachine(ccId) }
                    .onFailure {
                        log.warn("ccproxy delete for machine {} failed: {}", machine.id, it.message)
                    }
            }
            machine.ccproxyMachineId = null
            machine.aiMode = AiMode.NEWAPI
            machine.aiStatus = AiStatus.READY
            machineRepository.save(machine)
            events.record(
                machine,
                AI_SWITCH,
                DONE,
                "switched back to newapi (token $tokenId); the ccproxy registration is released",
            )
        } catch (e: Exception) {
            events.record(
                machine,
                AI_SWITCH,
                FAILED,
                "switch to newapi failed: ${e.message}",
                ERROR,
                cause = e,
            )
            throw e
        }
        return machine
    }

    /**
     * The birth-time login start for a machine created with aiMode=ccproxy, run after the
     * provisioner's transaction has committed. Deliberately not transactional: the login start
     * blocks on ccproxy's own provisioning of the machine (up to two minutes), and the poller it
     * hands off to writes aiStatus from another thread — a transaction open across either would
     * have its closing save overwrite what the poller wrote. Every save here commits at once.
     */
    @Async
    fun beginLoginAfterProvision(machineId: IdType) {
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
        try {
            beginLogin(machine)
        } catch (e: Exception) {
            events.record(
                machine,
                AI_LOGIN,
                FAILED,
                "ccproxy login could not start: ${e.message}",
                ERROR,
                cause = e,
            )
            machine.aiStatus = AiStatus.ERROR
            machineRepository.save(machine)
        }
    }

    /** Cancel the machine's current login-request if one is in progress (best-effort). */
    private fun cancelActiveLogin(machine: Machine, ccId: Long) {
        val m = runCatching { ccproxyClient.getMachine(ccId) }.getOrNull() ?: return
        m.currentLoginRequestId?.let { lrId ->
            runCatching { ccproxyClient.cancelLogin(lrId) }
                .onSuccess {
                    events.record(
                        machine,
                        AI_LOGIN,
                        LOGIN_CANCELLED,
                        "cancelled the previous ccproxy login request $lrId, never completed",
                        detail = "ccproxyMachineId=$ccId\nloginRequestId=$lrId\nstatus=${m.status}",
                    )
                }
                .onFailure {
                    events.record(
                        machine,
                        AI_LOGIN,
                        LOGIN_CANCELLED,
                        "cancelling the previous ccproxy login request $lrId failed: ${it.message}",
                        WARN,
                        cause = it,
                    )
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
