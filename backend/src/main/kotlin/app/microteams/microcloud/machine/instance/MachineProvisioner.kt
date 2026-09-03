/*
 *  Description: The Proxmox side of a machine's lifecycle — every method is @Async and lands a
 *               terminal status. provision() creates an LXC from the template on the placement,
 *               applies the leased IP, waits for it to run, and (optionally) SSHs in to run
 *               init-machine.py (-> RUNNING / ERROR). startCt / stopCt run the matching pct task
 *               (-> RUNNING / STOPPED / ERROR); destroyCt tears the CT down, releases its IP, and
 *               soft-deletes the row. A machine still provisioning (no vmid yet) has no CT to act on.
 *               Every step writes to the machine's event log (MachineEventRecorder): each Proxmox
 *               task with its UPID and duration, the SSH wait, the init output, and every failure.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.instance

import app.microteams.microcloud.common.config.MicroCloudConfig
import app.microteams.microcloud.machine.MachineKind
import app.microteams.microcloud.machine.ai.AiMode
import app.microteams.microcloud.machine.ai.AiProviderRegistry
import app.microteams.microcloud.machine.ai.AiStatus
import app.microteams.microcloud.machine.ai.CcproxyClient
import app.microteams.microcloud.machine.instance.MachineEventAction.DELETE
import app.microteams.microcloud.machine.instance.MachineEventAction.PROVISION
import app.microteams.microcloud.machine.instance.MachineEventLevel.ERROR
import app.microteams.microcloud.machine.instance.MachineEventLevel.WARN
import app.microteams.microcloud.machine.instance.MachineEventPhase.AI_SETUP_FAILED
import app.microteams.microcloud.machine.instance.MachineEventPhase.CCPROXY_REGISTERED
import app.microteams.microcloud.machine.instance.MachineEventPhase.DONE
import app.microteams.microcloud.machine.instance.MachineEventPhase.FAILED
import app.microteams.microcloud.machine.instance.MachineEventPhase.INIT_DONE
import app.microteams.microcloud.machine.instance.MachineEventPhase.PVE_TASK_DONE
import app.microteams.microcloud.machine.instance.MachineEventPhase.PVE_TASK_SUBMITTED
import app.microteams.microcloud.machine.instance.MachineEventPhase.RUNNING
import app.microteams.microcloud.machine.instance.MachineEventPhase.SSH_REACHABLE
import app.microteams.microcloud.machine.instance.MachineEventPhase.STARTED
import app.microteams.microcloud.machine.network.Network
import app.microteams.microcloud.machine.network.NetworkService
import app.microteams.microcloud.machine.placement.Placement
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.machine.placement.effectiveKind
import app.microteams.microcloud.machine.proxmox.OperatorSsh
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.proxmox.ProxmoxCluster
import app.microteams.microcloud.machine.proxmox.ProxmoxService
import app.microteams.microcloud.machine.template.MachineTemplateRepository
import app.microteams.microcloud.machine.template.TemplateUpload
import app.microteams.microcloud.machine.template.TemplateUploadRepository
import app.microteams.microcloud.machine.template.TemplateUploadStatus
import java.io.File
import java.security.SecureRandom
import java.time.LocalDateTime
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class MachineProvisioner(
    private val config: MicroCloudConfig,
    private val proxmoxClient: ProxmoxClient,
    private val proxmoxService: ProxmoxService,
    private val placementService: PlacementService,
    private val networkService: NetworkService,
    private val templateUploadRepository: TemplateUploadRepository,
    private val templateRepository: MachineTemplateRepository,
    private val machineRepository: MachineRepository,
    private val operatorSsh: OperatorSsh,
    private val aiRegistry: AiProviderRegistry,
    private val ccproxyClient: CcproxyClient,
    private val events: MachineEventRecorder,
) {
    private val log = LoggerFactory.getLogger(MachineProvisioner::class.java)
    private val random = SecureRandom()

    /** The Proxmox coordinates a machine lives at: cluster + node + vmid. */
    private fun clusterOf(machine: Machine): ProxmoxCluster =
        proxmoxService.getCluster(placementService.getPlacement(machine.placementId!!).clusterId!!)

    /** The machine's kind = the kind of the placement it lives on (the authoritative source). */
    private fun kindOf(machine: Machine): MachineKind =
        placementService.getPlacement(machine.placementId!!).effectiveKind

    /** Kick off provisioning in the background; the caller's create returns immediately. */
    @Async
    @Transactional
    fun provision(machineId: Long) {
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
        try {
            val placement = placementService.getPlacement(machine.placementId!!)
            val network = networkService.getNetwork(machine.networkId!!)
            val cluster = proxmoxService.getCluster(placement.clusterId!!)
            val node = placement.node!!
            val kind = placement.effectiveKind

            // createMachine only lands on a placement where the template is DONE-uploaded, so this
            // always resolves; the throw is a defensive guard.
            val upload =
                templateUploadRepository
                    .findByTemplateIdAndPlacementId(machine.templateId!!, machine.placementId!!)
                    .filter { it.status == TemplateUploadStatus.DONE }
                    .orElseThrow {
                        IllegalStateException(
                            "template ${machine.templateId} is not uploaded to placement " +
                                "${machine.placementId}"
                        )
                    }
            events.record(
                machine,
                PROVISION,
                STARTED,
                "provisioning started on ${cluster.name}/$node as ${kind.wire}",
                detail =
                    "cluster=${cluster.name}\nnode=$node\nkind=${kind.wire}\n" +
                        "template=${templateName(machine)}\noffering=${machine.offeringId}\n" +
                        "cores=${machine.cores}\nmemoryMb=${machine.memoryMb}\n" +
                        "diskGb=${machine.diskGb}\nip=${machine.ip}\naiMode=${machine.aiMode}",
            )

            // Resolve the AI provider and mint/prepare its per-machine config BEFORE init, so the
            // init-machine.py run can apply it. AI setup is orthogonal to the machine: a failure
            // here only marks aiStatus=ERROR, never fails the machine.
            val aiProvider = aiRegistry.forMode(machine.aiMode)
            val aiInitSuffix =
                try {
                    aiProvider.prepareInit(machine)
                } catch (e: Exception) {
                    events.record(
                        machine,
                        PROVISION,
                        AI_SETUP_FAILED,
                        "AI setup (${machine.aiMode}) failed before init: ${e.message}",
                        ERROR,
                        cause = e,
                    )
                    machine.aiStatus = AiStatus.ERROR
                    ""
                }

            // Authorize the operator key AND (if ccproxy is wired) the ccproxy operator key on the
            // LOGIN USER, so both can SSH in after hardening disables root login: MicroCloud for a
            // later newapi-restore, ccproxy for its own provision/login. Appended to init for every
            // machine, independent of aiMode.
            val initSuffix = aiInitSuffix + authorizedKeyArgs()

            when (kind) {
                MachineKind.PROXMOX_LXC ->
                    provisionLxc(machine, upload, placement, network, cluster, initSuffix)
                MachineKind.PROXMOX_VM ->
                    provisionVm(machine, upload, placement, network, cluster, initSuffix)
            }

            machine.status = MachineStatus.RUNNING
            // Birth-init on ccproxy: register the machine so its Claude is pointed at the engine
            // (unregistered → tunneled through, no account consumed) from birth, ready for a later
            // subscription switch. Best-effort — a failure never affects the machine or its newapi.
            // Before onReady, because a machine created with aiMode=ccproxy starts its login in
            // onReady and needs the registration to exist.
            registerWithCcproxy(machine)
            if (machine.aiStatus != AiStatus.ERROR) {
                try {
                    aiProvider.onReady(machine)
                } catch (e: Exception) {
                    events.record(
                        machine,
                        PROVISION,
                        AI_SETUP_FAILED,
                        "AI setup (${machine.aiMode}) failed: ${e.message}",
                        ERROR,
                        cause = e,
                    )
                    machine.aiStatus = AiStatus.ERROR
                }
            }
            machineRepository.save(machine)
            events.record(
                machine,
                PROVISION,
                RUNNING,
                "machine is running as ${kind.wire} ${machine.vmid} at ${machine.ip}",
            )
        } catch (e: Exception) {
            events.record(
                machine,
                PROVISION,
                FAILED,
                "provisioning failed: ${e.message}",
                ERROR,
                cause = e,
            )
            machine.status = MachineStatus.ERROR
            machineRepository.save(machine)
        }
    }

    private fun templateName(machine: Machine): String? =
        templateRepository.findById(machine.templateId!!).map { it.name }.orElse(null)

    /**
     * Submit-and-await one Proxmox task, recording its submission (with the UPID) and completion
     * (with the duration) on the machine's event log. A task that fails or times out throws from
     * [ProxmoxClient.waitForTask] with the UPID in the message, so the caller's FAILED event
     * carries it — the `qm start` lock timeout of 2026-09-03 was only visible in Proxmox's own task
     * index until then.
     */
    private fun awaitTask(
        machine: Machine,
        action: MachineEventAction,
        what: String,
        cluster: ProxmoxCluster,
        upid: String,
    ) {
        events.record(machine, action, PVE_TASK_SUBMITTED, "$what submitted", detail = "upid=$upid")
        val started = System.nanoTime()
        proxmoxClient.waitForTask(cluster, upid, timeout())
        val ms = (System.nanoTime() - started) / 1_000_000
        events.record(
            machine,
            action,
            PVE_TASK_DONE,
            "$what finished in $ms ms",
            detail = "upid=$upid\nduration_ms=$ms",
        )
    }

    /**
     * `pct start` / `qm start` returning does NOT mean the guest is reachable — it's still booting
     * (sshd not up, network not ready). Wait until TCP :22 accepts a connection, and record how
     * long that took.
     */
    private fun awaitSsh(machine: Machine) {
        val started = System.nanoTime()
        operatorSsh.waitForSsh(machine.ip!!, config.provisioning.sshReadyTimeoutSeconds)
        val ms = (System.nanoTime() - started) / 1_000_000
        events.record(
            machine,
            PROVISION,
            SSH_REACHABLE,
            "${machine.ip} accepts SSH connections after $ms ms",
            detail = "duration_ms=$ms",
        )
    }

    /**
     * `--authorized-key` args for init-machine.py: the operator key plus, when ccproxy is wired,
     * the ccproxy operator key (fetched best-effort). Both are authorized on the login user.
     */
    private fun authorizedKeyArgs(): String {
        val keys = mutableListOf<String>()
        operatorSsh.publicKey()?.takeIf { it.isNotBlank() }?.let { keys += it }
        if (ccproxyClient.isConfigured()) {
            try {
                ccproxyClient.getSshPubkey().takeIf { it.isNotBlank() }?.let { keys += it }
            } catch (e: Exception) {
                log.warn("could not fetch ccproxy operator ssh-pubkey: {}", e.message)
            }
        }
        // Keys are base64-ish (no single quotes), so single-quoting is safe.
        return keys.joinToString("") { " --authorized-key '$it'" }
    }

    /** Register the machine with ccproxy at birth (best-effort); records the ccproxy machine id. */
    private fun registerWithCcproxy(machine: Machine) {
        if (!ccproxyClient.isConfigured() || machine.ccproxyMachineId != null) return
        if (machine.ip.isNullOrBlank() || machine.loginUser.isNullOrBlank()) return
        try {
            val m =
                ccproxyClient.createMachine(
                    host = machine.ip!!,
                    sshUser = machine.loginUser!!,
                    sshPort = 22,
                    label = machine.hostname,
                )
            machine.ccproxyMachineId = m.id
            events.record(
                machine,
                PROVISION,
                CCPROXY_REGISTERED,
                "registered with ccproxy as machine ${m.id}",
                detail = "ccproxyMachineId=${m.id}\nstatus=${m.status}",
            )
        } catch (e: Exception) {
            events.record(
                machine,
                PROVISION,
                CCPROXY_REGISTERED,
                "ccproxy registration failed, the machine runs without it: ${e.message}",
                WARN,
                cause = e,
            )
        }
    }

    /** LXC: pct create from the template's vztmpl volid, apply the IP, then init over root SSH. */
    private fun provisionLxc(
        machine: Machine,
        upload: TemplateUpload,
        placement: Placement,
        network: Network,
        cluster: ProxmoxCluster,
        aiInitSuffix: String,
    ) {
        val node = placement.node!!
        val ostemplate =
            upload.volid
                ?: throw IllegalStateException(
                    "LXC template upload ${upload.id} has no vztmpl volid"
                )
        val vmid = proxmoxClient.nextVmid(cluster)
        val params = buildMap {
            put("vmid", vmid.toString())
            put("ostemplate", ostemplate)
            put("hostname", machine.hostname!!)
            put("cores", machine.cores.toString())
            put("memory", machine.memoryMb.toString())
            put("swap", "512")
            put("rootfs", "${placement.storage}:${machine.diskGb}")
            put("unprivileged", "1")
            put("features", "nesting=1")
            put(
                "net0",
                "name=eth0,bridge=${network.bridge},ip=${machine.ip}/${network.prefixLength}," +
                    "gw=${network.gateway}",
            )
            put("pool", placement.pool!!)
            put("password", randomPassword())
            operatorSsh.publicKey()?.let { put("ssh-public-keys", it) }
            put("start", "1")
        }

        awaitTask(
            machine,
            PROVISION,
            "pct create CT$vmid on $node (from $ostemplate, started)",
            cluster,
            proxmoxClient.createLxc(cluster, node, params),
        )
        machine.vmid = vmid
        machineRepository.save(machine)

        runInit(machine, network.gateway!!, aiInitSuffix)
    }

    /**
     * VM: clone the baked VM template, then set it up in two stages, mirroring the operator's
     * manual flow and keeping init-machine.py in the loop:
     * 1. cloud-init (at clone): create the login user with its key + a static IP, so the machine is
     *    reachable as the login user the moment it boots. The operator key is injected ALONGSIDE
     *    the login user's key so the backend can still SSH in for stage 2 (the login user gets
     *    passwordless sudo from the template's cloud-init default).
     * 2. init-machine.py (after boot): the backend SSHes in as the login user with the operator key
     *    and pipes templates/vm/<template>/init-machine.py to `sudo python3 -` to install per-user
     *    software (Claude Code / AI tools) and finish setup. No `--ip` — cloud-init already set it.
     */
    private fun provisionVm(
        machine: Machine,
        upload: TemplateUpload,
        placement: Placement,
        network: Network,
        cluster: ProxmoxCluster,
        aiInitSuffix: String,
    ) {
        val node = placement.node!!
        val templateVmid =
            upload.templateVmid
                ?: throw IllegalStateException(
                    "VM template upload ${upload.id} has no baked template vmid"
                )
        val vmid = proxmoxClient.nextVmid(cluster)
        awaitTask(
            machine,
            PROVISION,
            "qm clone VM$vmid from template VM$templateVmid on $node",
            cluster,
            proxmoxClient.cloneVm(
                cluster,
                node,
                templateVmid,
                buildMap {
                    put("newid", vmid.toString())
                    put("name", machine.hostname!!)
                    put("pool", placement.pool!!)
                    put("full", "1")
                },
            ),
        )
        machine.vmid = vmid
        machineRepository.save(machine)

        // cloud-init keys = the login user's key + the operator key (so the backend can SSH in for
        // init). Multiple keys are newline-separated; the whole blob is URL-encoded once.
        val cloudInitKeys =
            listOfNotNull(machine.sshPubkey?.ifBlank { null }, operatorSsh.publicKey())
                .joinToString("\n")
        proxmoxClient.setVmConfig(
            cluster,
            node,
            vmid,
            buildMap {
                put("cores", machine.cores.toString())
                put("memory", machine.memoryMb.toString())
                put("ciuser", machine.loginUser!!)
                if (cloudInitKeys.isNotBlank())
                    put("sshkeys", proxmoxClient.sshkeysParam(cloudInitKeys))
                put("ipconfig0", "ip=${machine.ip}/${network.prefixLength},gw=${network.gateway}")
            },
        )
        // The resize is a task that holds the VM's config lock while the volume grows; a start
        // issued before it finishes fails with "can't lock file ... got timeout" whenever the
        // storage is slow enough for the resize to outlast qm start's 10 s lock wait (three
        // times on pve119 on 2026-09-03). Wait for it like every other task here.
        awaitTask(
            machine,
            PROVISION,
            "qm resize VM$vmid scsi0 to ${machine.diskGb}G",
            cluster,
            proxmoxClient.resizeVmDisk(cluster, node, vmid, "scsi0", "${machine.diskGb}G"),
        )
        awaitTask(
            machine,
            PROVISION,
            "qm start VM$vmid",
            cluster,
            proxmoxClient.startVm(cluster, node, vmid),
        )
        awaitSsh(machine)
        runVmInit(machine, aiInitSuffix)
    }

    /**
     * Stage 2 of VM provisioning: pipe the template's init-machine.py to the machine over SSH (as
     * the login user, with the operator key) and run it with sudo. No-op when disabled, when there
     * is no operator key to log in with, or when the template ships no init-machine.py.
     */
    private fun runVmInit(machine: Machine, aiInitSuffix: String) {
        val command = config.provisioning.vmInitCommand?.takeIf { it.isNotBlank() } ?: return
        if (operatorSsh.privateKeyPath() == null || operatorSsh.publicKey() == null) return
        val templateName = templateName(machine) ?: return
        val script = File("${config.templatesDir}/vm/$templateName/init-machine.py")
        if (!script.isFile) {
            log.info("VM template {} ships no init-machine.py; skipping VM init", templateName)
            return
        }
        val remote =
            command
                .replace("{user}", machine.loginUser ?: "")
                .replace("{sshPubkey}", machine.sshPubkey ?: "") + aiInitSuffix
        val started = System.nanoTime()
        val output =
            operatorSsh.runScript(
                machine.loginUser!!,
                machine.ip!!,
                script,
                remote,
                config.provisioning.taskTimeoutSeconds,
            )
        val ms = (System.nanoTime() - started) / 1_000_000
        events.record(
            machine,
            PROVISION,
            INIT_DONE,
            "init-machine finished in $ms ms",
            detail = output.ifBlank { null },
        )
    }

    /** Async start (pct/qm per kind): STARTING -> RUNNING / ERROR. */
    @Async
    @Transactional
    fun startCt(machineId: Long) =
        runTask(machineId, MachineEventAction.START, MachineStatus.RUNNING) { machine, cluster, node
            ->
            machine.vmid?.let {
                when (kindOf(machine)) {
                    MachineKind.PROXMOX_LXC ->
                        "pct start CT$it on $node" to proxmoxClient.startLxc(cluster, node, it)
                    MachineKind.PROXMOX_VM ->
                        "qm start VM$it on $node" to proxmoxClient.startVm(cluster, node, it)
                }
            }
        }

    /**
     * Async graceful shutdown (pct/qm per kind): STOPPING -> STOPPED / ERROR. Guest flushes its FS.
     */
    @Async
    @Transactional
    fun shutdownCt(machineId: Long) =
        runTask(machineId, MachineEventAction.SHUTDOWN, MachineStatus.STOPPED) {
            machine,
            cluster,
            node ->
            machine.vmid?.let {
                when (kindOf(machine)) {
                    MachineKind.PROXMOX_LXC ->
                        "pct shutdown CT$it on $node" to
                            proxmoxClient.shutdownLxc(cluster, node, it)
                    MachineKind.PROXMOX_VM ->
                        "qm shutdown VM$it on $node" to proxmoxClient.shutdownVm(cluster, node, it)
                }
            }
        }

    /**
     * Async HARD stop (pct/qm per kind): STOPPING -> STOPPED / ERROR. Force path; prefer shutdown.
     */
    @Async
    @Transactional
    fun stopCt(machineId: Long) =
        runTask(machineId, MachineEventAction.STOP, MachineStatus.STOPPED) { machine, cluster, node
            ->
            machine.vmid?.let {
                when (kindOf(machine)) {
                    MachineKind.PROXMOX_LXC ->
                        "pct stop CT$it on $node" to proxmoxClient.stopLxc(cluster, node, it)
                    MachineKind.PROXMOX_VM ->
                        "qm stop VM$it on $node" to proxmoxClient.stopVm(cluster, node, it)
                }
            }
        }

    /**
     * Async destroy (pct/qm per kind): DELETING -> torn down, IP released, and the row soft-deleted
     * (status DELETED + deletedAt, hidden from every machine read). The row stays so the machine's
     * event log keeps its owner and remains readable after the machine is gone.
     */
    @Async
    @Transactional
    fun destroyCt(machineId: Long) {
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
        try {
            machine.vmid?.let { vmid ->
                val placement = placementService.getPlacement(machine.placementId!!)
                val cluster = proxmoxService.getCluster(placement.clusterId!!)
                val node = placement.node!!
                when (placement.effectiveKind) {
                    // pct destroy --purge --force tears down a running CT in one shot.
                    MachineKind.PROXMOX_LXC ->
                        awaitTask(
                            machine,
                            DELETE,
                            "pct destroy CT$vmid on $node",
                            cluster,
                            proxmoxClient.destroyLxc(cluster, node, vmid),
                        )
                    // qm destroy REFUSES a running VM ("VM N is running - destroy failed"), unlike
                    // pct destroy, so a running VM is qm-stopped first. Two tasks, each recorded.
                    MachineKind.PROXMOX_VM -> {
                        if (proxmoxClient.vmStatus(cluster, node, vmid) != "stopped")
                            awaitTask(
                                machine,
                                DELETE,
                                "qm stop VM$vmid on $node",
                                cluster,
                                proxmoxClient.stopVm(cluster, node, vmid),
                            )
                        awaitTask(
                            machine,
                            DELETE,
                            "qm destroy VM$vmid on $node",
                            cluster,
                            proxmoxClient.destroyVm(cluster, node, vmid),
                        )
                    }
                }
            }
            // AI teardown, independent of the machine's current aiMode (a switched machine still
            // holds BOTH a newapi token and a ccproxy registration): release the newapi token and
            // tear the machine down on ccproxy (frees its bound account). Both best-effort.
            runCatching { aiRegistry.forMode(AiMode.NEWAPI).teardown(machine) }
            machine.ccproxyMachineId?.let { id -> runCatching { ccproxyClient.deleteMachine(id) } }
            networkService.releaseIpsFor(machine.id!!)
            machine.status = MachineStatus.DELETED
            machine.deletedAt = LocalDateTime.now()
            machineRepository.save(machine)
            events.record(
                machine,
                DELETE,
                DONE,
                "machine deleted: guest destroyed, AI registrations released, ${machine.ip} freed",
            )
        } catch (e: Exception) {
            events.record(machine, DELETE, FAILED, "delete failed: ${e.message}", ERROR, cause = e)
            machine.status = MachineStatus.ERROR
            machineRepository.save(machine)
        }
    }

    private fun timeout() = config.provisioning.taskTimeoutSeconds

    /**
     * Run one Proxmox task on the machine's guest — [submit] returns what it submitted and the
     * UPID, or null when there is no guest yet — then land the given terminal status (or ERROR),
     * recording the task and the outcome under [action].
     */
    private fun runTask(
        machineId: Long,
        action: MachineEventAction,
        terminal: MachineStatus,
        submit: (Machine, ProxmoxCluster, String) -> Pair<String, String>?,
    ) {
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
        try {
            val cluster = clusterOf(machine)
            val node = placementService.getPlacement(machine.placementId!!).node!!
            submit(machine, cluster, node)?.let { (what, upid) ->
                awaitTask(machine, action, what, cluster, upid)
            }
            machine.status = terminal
            events.record(machine, action, DONE, "machine is ${terminal.name.lowercase()}")
        } catch (e: Exception) {
            events.record(
                machine,
                action,
                FAILED,
                "${action.name.lowercase()} failed: ${e.message}",
                ERROR,
                cause = e,
            )
            machine.status = MachineStatus.ERROR
        }
        machineRepository.save(machine)
    }

    /** Run init-machine.py inside the fresh container over SSH-as-root, if configured. */
    private fun runInit(machine: Machine, gateway: String, aiInitSuffix: String) {
        val command = config.provisioning.initCommand?.takeIf { it.isNotBlank() } ?: return
        if (operatorSsh.privateKeyPath() == null) return
        awaitSsh(machine)
        val remote =
            command
                .replace("{user}", machine.loginUser ?: "")
                .replace("{sshPubkey}", machine.sshPubkey ?: "")
                .replace("{ip}", machine.ip ?: "")
                .replace("{gateway}", gateway) + aiInitSuffix
        val started = System.nanoTime()
        val output =
            operatorSsh.run("root", machine.ip!!, remote, config.provisioning.taskTimeoutSeconds)
        val ms = (System.nanoTime() - started) / 1_000_000
        events.record(
            machine,
            PROVISION,
            INIT_DONE,
            "init-machine finished in $ms ms",
            detail = output.ifBlank { null },
        )
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return "Mc" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
