/*
 *  Description: The Proxmox side of a machine's lifecycle — every method is @Async and lands a
 *               terminal status. provision() creates an LXC from the template on the placement,
 *               applies the leased IP, waits for it to run, and (optionally) SSHs in to run
 *               init-machine.py (-> RUNNING / ERROR); every new guest is put behind its Proxmox
 *               firewall (GuestFirewall) before it first boots. startCt / stopCt run the matching pct task
 *               (-> RUNNING / STOPPED / ERROR); destroyCt tears the CT down, releases its IP, and
 *               soft-deletes the row. provision() and destroyCt() hold the same per-machine lock, so a
 *               delete that arrives mid-create waits for the provision to commit, then reads the guest
 *               it created; the provision, finding the machine deleted, stops short of RUNNING.
 *               A task on an existing guest that Proxmox refuses because the guest is locked (a
 *               maintenance fstrim) is resubmitted for a bounded time.
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
import app.microteams.microcloud.machine.proxmox.GuestOwnership
import app.microteams.microcloud.machine.proxmox.OperatorSsh
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.proxmox.ProxmoxCluster
import app.microteams.microcloud.machine.proxmox.ProxmoxGuestLocked
import app.microteams.microcloud.machine.proxmox.ProxmoxService
import app.microteams.microcloud.machine.proxmox.ProxmoxTaskTimeout
import app.microteams.microcloud.machine.proxmox.guestIsolationRules
import app.microteams.microcloud.machine.proxmox.hostPortEndpoint
import app.microteams.microcloud.machine.proxmox.relayEndpoint
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
        lockMachine(machineId)
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
        if (machine.status != MachineStatus.PROVISIONING) {
            // Deleted before provisioning began: nothing was created, and destroyCt frees the IP.
            events.record(
                machine,
                PROVISION,
                DONE,
                "provisioning skipped: the machine was deleted before it started",
                WARN,
            )
            return
        }
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

            if (!finishProvisioning(machine, MachineStatus.RUNNING)) {
                // destroyCt is waiting on lockMachine and destroys the guest once this commits.
                events.record(
                    machine,
                    PROVISION,
                    DONE,
                    "provisioning stopped: the machine was deleted while it was being created; " +
                        "the delete destroys ${kind.wire} ${machine.vmid}",
                    WARN,
                )
                return
            }
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
            finishProvisioning(machine, MachineStatus.ERROR)
        }
    }

    /**
     * Write this provision's changes to [machine] (vmid, AI state), then move it from PROVISIONING
     * to [to]. The status is set by a conditional update rather than on the entity, which was read
     * minutes ago: a delete may have committed DELETING since, and that must stand. False when it
     * did.
     */
    private fun finishProvisioning(machine: Machine, to: MachineStatus): Boolean {
        machineRepository.saveAndFlush(machine)
        if (machineRepository.finishProvisioning(machine.id!!, to) == 0) return false
        machine.status = to
        return true
    }

    private fun templateName(machine: Machine): String? =
        templateRepository.findById(machine.templateId!!).map { it.name }.orElse(null)

    /**
     * Submit-and-await one Proxmox task, recording its submission (with the UPID) and completion
     * (with the duration) on the machine's event log. A task that fails or times out throws from
     * [ProxmoxClient.waitForTask] with the UPID in the message, so the caller's FAILED event
     * carries it — the `qm start` lock timeout of 2026-09-03 was only visible in Proxmox's own task
     * index until then.
     *
     * A task still running when the wait runs out has not failed, so it is polled for
     * [MicroCloudConfig.Provisioning.taskOutcomeTimeoutSeconds] more, with a WARN event saying so.
     * Only if that runs out too does [ProxmoxTaskTimeout] reach the caller, as an unknown outcome.
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
        try {
            proxmoxClient.waitForTask(cluster, upid, timeout())
        } catch (e: ProxmoxTaskTimeout) {
            val more = config.provisioning.taskOutcomeTimeoutSeconds
            events.record(
                machine,
                action,
                PVE_TASK_SUBMITTED,
                "$what still running after ${timeout()} s; its outcome is unknown, polling up to " +
                    "$more s more",
                WARN,
                detail = "upid=$upid",
            )
            proxmoxClient.waitForTask(cluster, upid, more)
        }
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
     * [awaitTask] for a task on a guest that already exists, whose request [submit] sends and
     * returns the UPID of. While Proxmox refuses it because the guest is locked
     * ([ProxmoxGuestLocked]: the guest was not touched) the request is sent again, backing off from
     * 2 s to 30 s, for up to [MicroCloudConfig.Provisioning.lockRetrySeconds]; each refusal is a
     * WARN event. A create is never resubmitted this way: its lock would be another create's.
     */
    private fun awaitGuestTask(
        machine: Machine,
        action: MachineEventAction,
        what: String,
        cluster: ProxmoxCluster,
        submit: () -> String,
    ) {
        val bound = config.provisioning.lockRetrySeconds
        val deadline = System.nanoTime() + bound * 1_000_000_000
        var backoff = 2L
        var attempt = 1
        while (true) {
            try {
                awaitTask(machine, action, what, cluster, submit())
                return
            } catch (e: ProxmoxGuestLocked) {
                val left = (deadline - System.nanoTime()) / 1_000_000_000
                if (left <= 0)
                    throw ProxmoxGuestLocked(
                        "$what: the guest stayed locked through $attempt attempts over $bound s: " +
                            e.message
                    )
                val wait = minOf(backoff, left)
                events.record(
                    machine,
                    action,
                    PVE_TASK_SUBMITTED,
                    "$what refused, the guest is locked; resubmitting in $wait s " +
                        "(attempt ${attempt + 1}, up to $bound s in all)",
                    WARN,
                    detail = e.message,
                )
                Thread.sleep(wait * 1000)
                backoff = minOf(backoff * 2, 30)
                attempt++
            }
        }
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
        lockGuest(cluster, vmid)
        val params = buildMap {
            put("vmid", vmid.toString())
            put("ostemplate", ostemplate)
            put("hostname", machine.hostname!!)
            put("description", "microcloud-machine:${machine.id}")
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
            // No start=1: Proxmox runs that start as a separate vzstart task the create task does
            // not wait for, so a start refused while pve119's fstrim sweep held the lock went
            // unseen until SSH never came up. The start below is awaited and retried.
        }

        val upid = proxmoxClient.createLxc(cluster, node, params)
        awaitCreate(machine, cluster, node, vmid) {
            awaitTask(
                machine,
                PROVISION,
                "pct create CT$vmid on $node (from $ostemplate)",
                cluster,
                upid,
            )
        }
        // Before the first start, so the container is never on the network unisolated.
        isolate(machine, cluster, node, vmid, vm = false)
        awaitGuestTask(machine, PROVISION, "pct start CT$vmid on $node", cluster) {
            proxmoxClient.startLxc(cluster, node, vmid)
        }

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
        lockGuest(cluster, vmid)
        val upid =
            proxmoxClient.cloneVm(
                cluster,
                node,
                templateVmid,
                buildMap {
                    put("newid", vmid.toString())
                    put("name", machine.hostname!!)
                    put("description", "microcloud-machine:${machine.id}")
                    put("pool", placement.pool!!)
                    put("full", "1")
                },
            )
        awaitCreate(machine, cluster, node, vmid) {
            awaitTask(
                machine,
                PROVISION,
                "qm clone VM$vmid from template VM$templateVmid on $node",
                cluster,
                upid,
            )
        }

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
        // Before the first boot, so the guest is never on the network unisolated.
        isolate(machine, cluster, node, vmid, vm = true)
        // The resize is a task that holds the VM's config lock while the volume grows; a start
        // issued before it finishes fails with "can't lock file ... got timeout" whenever the
        // storage is slow enough for the resize to outlast qm start's 10 s lock wait (three
        // times on pve119 on 2026-09-03). Wait for it like every other task here.
        awaitGuestTask(
            machine,
            PROVISION,
            "qm resize VM$vmid scsi0 to ${machine.diskGb}G",
            cluster,
        ) {
            proxmoxClient.resizeVmDisk(cluster, node, vmid, "scsi0", "${machine.diskGb}G")
        }
        awaitGuestTask(machine, PROVISION, "qm start VM$vmid", cluster) {
            proxmoxClient.startVm(cluster, node, vmid)
        }
        awaitSsh(machine)
        runVmInit(machine, aiInitSuffix)
    }

    /** Put the new guest behind its Proxmox firewall (see guestIsolationRules). */
    private fun isolate(
        machine: Machine,
        cluster: ProxmoxCluster,
        node: String,
        vmid: Int,
        vm: Boolean,
    ) {
        val reachable =
            listOfNotNull(relayEndpoint(config.newapi.machineBaseUrl)) +
                config.provisioning.guestReachable
                    .filter { it.isNotBlank() }
                    .map(::hostPortEndpoint)
        proxmoxClient.isolateGuest(
            cluster,
            node,
            vmid,
            vm,
            machine.ip!!,
            guestIsolationRules(
                networkService.guestRanges(),
                reachable,
                config.provisioning.guestBlocked.filter { it.isNotBlank() }.map(::hostPortEndpoint),
            ),
        )
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

    @Async
    @Transactional
    fun suspendMachine(machineId: Long) =
        runTask(machineId, MachineEventAction.SUSPEND, MachineStatus.SUSPENDED) {
            machine,
            cluster,
            node ->
            val vmid = checkNotNull(machine.vmid) { "machine has no guest" }
            when (kindOf(machine)) {
                MachineKind.PROXMOX_LXC ->
                    "shutdown CT$vmid (retain disks) on $node" to
                        {
                            proxmoxClient.shutdownLxc(cluster, node, vmid)
                        }
                MachineKind.PROXMOX_VM ->
                    "hibernate VM$vmid on $node" to { proxmoxClient.suspendVm(cluster, node, vmid) }
            }
        }

    @Async
    @Transactional
    fun resumeMachine(machineId: Long) =
        runTask(machineId, MachineEventAction.RESUME, MachineStatus.RUNNING) {
            machine,
            cluster,
            node ->
            val vmid = checkNotNull(machine.vmid) { "machine has no guest" }
            when (kindOf(machine)) {
                MachineKind.PROXMOX_LXC ->
                    "start CT$vmid (existing disks) on $node" to
                        {
                            proxmoxClient.startLxc(cluster, node, vmid)
                        }
                MachineKind.PROXMOX_VM ->
                    "resume VM$vmid on $node" to { proxmoxClient.resumeVm(cluster, node, vmid) }
            }
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
                        "pct start CT$it on $node" to { proxmoxClient.startLxc(cluster, node, it) }
                    MachineKind.PROXMOX_VM ->
                        "qm start VM$it on $node" to { proxmoxClient.startVm(cluster, node, it) }
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
                            {
                                proxmoxClient.shutdownLxc(cluster, node, it)
                            }
                    MachineKind.PROXMOX_VM ->
                        "qm shutdown VM$it on $node" to
                            {
                                proxmoxClient.shutdownVm(cluster, node, it)
                            }
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
                        "pct stop CT$it on $node" to { proxmoxClient.stopLxc(cluster, node, it) }
                    MachineKind.PROXMOX_VM ->
                        "qm stop VM$it on $node" to { proxmoxClient.stopVm(cluster, node, it) }
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
        // Waits out a provision still running, so the vmid read next is the one it committed.
        lockMachine(machineId)
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
        try {
            machine.vmid?.let { vmid ->
                val placement = placementService.getPlacement(machine.placementId!!)
                val cluster = proxmoxService.getCluster(placement.clusterId!!)
                val node = placement.node!!
                lockGuest(cluster, vmid)
                val kind = placement.effectiveKind
                when (
                    proxmoxClient.guestOwnership(
                        cluster,
                        node,
                        vmid,
                        kind == MachineKind.PROXMOX_VM,
                        machine.id!!,
                        machine.hostname!!,
                        machine.ip!!,
                    )
                ) {
                    GuestOwnership.OURS -> destroyGuest(machine, DELETE, cluster, node, vmid, kind)
                    // This machine's guest is not on the cluster: its create never landed, or the
                    // id now belongs to a newer machine. Nothing to destroy, and refusing would
                    // keep the row in ERROR forever while every retry of the delete fails again
                    // (machines 1817 and 2039, whose ids 123 and 144 went to live machines).
                    GuestOwnership.ABSENT,
                    GuestOwnership.FOREIGN ->
                        events.record(
                            machine,
                            DELETE,
                            DONE,
                            "guest $vmid is not this machine's (absent or reused); nothing to destroy",
                        )
                    GuestOwnership.UNKNOWN ->
                        error(
                            "Guest $vmid cannot be proven absent or this machine's; not touching it"
                        )
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
     * Wait on the task creating guest [vmid], owning the vmid from submission. A create whose
     * outcome stays unknown may still land its guest; had the vmid been recorded only on success,
     * delete would skip the destroy and free the IP under a running guest, and the next machine
     * leased that IP (machine 1951's CT253 on 2026-09-29). A task that stopped with an error
     * normally leaves no guest, but if one with this machine's identity is there anyway it is
     * destroyed; then the vmid is dropped, so delete does not fail on a guest that is gone.
     */
    private fun awaitCreate(
        machine: Machine,
        cluster: ProxmoxCluster,
        node: String,
        vmid: Int,
        wait: () -> Unit,
    ) {
        machine.vmid = vmid
        machineRepository.save(machine)
        try {
            wait()
        } catch (e: ProxmoxTaskTimeout) {
            throw e
        } catch (e: Exception) {
            val kind = kindOf(machine)
            if (
                proxmoxClient.ownsGuest(
                    cluster,
                    node,
                    vmid,
                    kind == MachineKind.PROXMOX_VM,
                    machine.id!!,
                    machine.hostname!!,
                    machine.ip!!,
                )
            ) {
                try {
                    destroyGuest(machine, PROVISION, cluster, node, vmid, kind)
                } catch (cleanup: Exception) {
                    // The guest is still there: keep the vmid so delete destroys it later.
                    e.addSuppressed(cleanup)
                    throw e
                }
            }
            machine.vmid = null
            throw e
        }
    }

    /** Tear down guest [vmid] on [node], recording each Proxmox task under [action]. */
    private fun destroyGuest(
        machine: Machine,
        action: MachineEventAction,
        cluster: ProxmoxCluster,
        node: String,
        vmid: Int,
        kind: MachineKind,
    ) {
        when (kind) {
            // pct destroy --purge --force tears down a running CT in one shot.
            MachineKind.PROXMOX_LXC ->
                awaitGuestTask(machine, action, "pct destroy CT$vmid on $node", cluster) {
                    proxmoxClient.destroyLxc(cluster, node, vmid)
                }
            // qm destroy REFUSES a running VM ("VM N is running - destroy failed"), unlike pct
            // destroy, so a running VM is qm-stopped first. Two tasks, each recorded.
            MachineKind.PROXMOX_VM -> {
                if (proxmoxClient.vmStatus(cluster, node, vmid) != "stopped")
                    awaitGuestTask(machine, action, "qm stop VM$vmid on $node", cluster) {
                        proxmoxClient.stopVm(cluster, node, vmid)
                    }
                awaitGuestTask(machine, action, "qm destroy VM$vmid on $node", cluster) {
                    proxmoxClient.destroyVm(cluster, node, vmid)
                }
            }
        }
    }

    private fun lockGuest(cluster: ProxmoxCluster, vmid: Int) {
        // Held through task completion and commit so creation cannot reuse an ID between
        // another worker's ownership check and destructive request.
        machineRepository.advisoryLock("microcloud-guest:${cluster.id}:$vmid")
    }

    /**
     * Held for the whole of provision() and destroyCt(), so a delete never reads the machine while
     * its create is in flight: provision() saves the vmid only when its transaction commits.
     */
    private fun lockMachine(machineId: Long) {
        machineRepository.advisoryLock("microcloud-machine:$machineId")
    }

    private fun verifyGuest(machine: Machine, cluster: ProxmoxCluster, node: String) {
        proxmoxClient.verifyGuestIdentity(
            cluster,
            node,
            machine.vmid!!,
            kindOf(machine) == MachineKind.PROXMOX_VM,
            machine.id!!,
            machine.hostname!!,
            machine.ip!!,
        )
    }

    /**
     * Run one Proxmox task on the machine's guest — [submit] returns what it is and the request
     * that sends it (see [awaitGuestTask]), or null when there is no guest yet — then land the
     * given terminal status (or ERROR), recording the task and the outcome under [action].
     */
    private fun runTask(
        machineId: Long,
        action: MachineEventAction,
        terminal: MachineStatus,
        submit: (Machine, ProxmoxCluster, String) -> Pair<String, () -> String>?,
    ) {
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
        try {
            val cluster = clusterOf(machine)
            val node = placementService.getPlacement(machine.placementId!!).node!!
            machine.vmid?.let {
                lockGuest(cluster, it)
                verifyGuest(machine, cluster, node)
            }
            submit(machine, cluster, node)?.let { (what, request) ->
                awaitGuestTask(machine, action, what, cluster, request)
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
