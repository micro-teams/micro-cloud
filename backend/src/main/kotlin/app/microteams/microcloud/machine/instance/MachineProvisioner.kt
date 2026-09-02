/*
 *  Description: The Proxmox side of a machine's lifecycle — every method is @Async and lands a
 *               terminal status. provision() creates an LXC from the template on the placement,
 *               applies the leased IP, waits for it to run, and (optionally) SSHs in to run
 *               init-machine.py (-> RUNNING / ERROR). startCt / stopCt run the matching pct task
 *               (-> RUNNING / STOPPED / ERROR); destroyCt tears the CT down, releases its IP, and
 *               removes the row. A machine still provisioning (no vmid yet) has no CT to act on.
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

            // Resolve the AI provider and mint/prepare its per-machine config BEFORE init, so the
            // init-machine.py run can apply it. AI setup is orthogonal to the machine: a failure
            // here only marks aiStatus=ERROR, never fails the machine.
            val aiProvider = aiRegistry.forMode(machine.aiMode)
            val aiInitSuffix =
                try {
                    aiProvider.prepareInit(machine)
                } catch (e: Exception) {
                    log.error("AI prepare for machine {} failed: {}", machine.id, e.message, e)
                    machine.aiStatus = AiStatus.ERROR
                    ""
                }

            // Authorize the operator key AND (if ccproxy is wired) the ccproxy operator key on the
            // LOGIN USER, so both can SSH in after hardening disables root login: MicroCloud for a
            // later newapi-restore, ccproxy for its own provision/login. Appended to init for every
            // machine, independent of aiMode.
            val initSuffix = aiInitSuffix + authorizedKeyArgs()

            when (placement.effectiveKind) {
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
                    log.error("AI setup for machine {} failed: {}", machine.id, e.message, e)
                    machine.aiStatus = AiStatus.ERROR
                }
            }
            machineRepository.save(machine)
            log.info(
                "machine {} is RUNNING ({} {})",
                machine.id,
                placement.effectiveKind.wire,
                machine.vmid,
            )
        } catch (e: Exception) {
            log.error("provisioning machine {} failed: {}", machineId, e.message, e)
            machine.status = MachineStatus.ERROR
            machineRepository.save(machine)
        }
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
            log.info("machine {} registered with ccproxy as {}", machine.id, m.id)
        } catch (e: Exception) {
            log.warn("ccproxy birth-init for machine {} failed: {}", machine.id, e.message)
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

        log.info("provisioning machine {} as {}/CT{}", machine.id, node, vmid)
        proxmoxClient.waitForTask(
            cluster,
            proxmoxClient.createLxc(cluster, node, params),
            config.provisioning.taskTimeoutSeconds,
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
        log.info(
            "provisioning machine {} as {}/VM{} (clone of {})",
            machine.id,
            node,
            vmid,
            templateVmid,
        )
        proxmoxClient.waitForTask(
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
            config.provisioning.taskTimeoutSeconds,
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
        proxmoxClient.resizeVmDisk(cluster, node, vmid, "scsi0", "${machine.diskGb}G")
        proxmoxClient.waitForTask(
            cluster,
            proxmoxClient.startVm(cluster, node, vmid),
            config.provisioning.taskTimeoutSeconds,
        )
        operatorSsh.waitForSsh(machine.ip!!, config.provisioning.sshReadyTimeoutSeconds)
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
        val templateName =
            templateRepository.findById(machine.templateId!!).map { it.name }.orElse(null) ?: return
        val script = File("${config.templatesDir}/vm/$templateName/init-machine.py")
        if (!script.isFile) {
            log.info("VM template {} ships no init-machine.py; skipping VM init", templateName)
            return
        }
        val remote =
            command
                .replace("{user}", machine.loginUser ?: "")
                .replace("{sshPubkey}", machine.sshPubkey ?: "") + aiInitSuffix
        operatorSsh.runScript(
            machine.loginUser!!,
            machine.ip!!,
            script,
            remote,
            config.provisioning.taskTimeoutSeconds,
        )
        log.info("VM init-machine for machine {} succeeded", machine.id)
    }

    /** Async start (pct/qm per kind): STARTING -> RUNNING / ERROR. */
    @Async
    @Transactional
    fun startCt(machineId: Long) =
        runTask(machineId, MachineStatus.RUNNING) { machine, cluster, node ->
            machine.vmid?.let {
                val upid =
                    when (kindOf(machine)) {
                        MachineKind.PROXMOX_LXC -> proxmoxClient.startLxc(cluster, node, it)
                        MachineKind.PROXMOX_VM -> proxmoxClient.startVm(cluster, node, it)
                    }
                proxmoxClient.waitForTask(cluster, upid, timeout())
            }
        }

    /**
     * Async graceful shutdown (pct/qm per kind): STOPPING -> STOPPED / ERROR. Guest flushes its FS.
     */
    @Async
    @Transactional
    fun shutdownCt(machineId: Long) =
        runTask(machineId, MachineStatus.STOPPED) { machine, cluster, node ->
            machine.vmid?.let {
                val upid =
                    when (kindOf(machine)) {
                        MachineKind.PROXMOX_LXC -> proxmoxClient.shutdownLxc(cluster, node, it)
                        MachineKind.PROXMOX_VM -> proxmoxClient.shutdownVm(cluster, node, it)
                    }
                proxmoxClient.waitForTask(cluster, upid, timeout())
            }
        }

    /**
     * Async HARD stop (pct/qm per kind): STOPPING -> STOPPED / ERROR. Force path; prefer shutdown.
     */
    @Async
    @Transactional
    fun stopCt(machineId: Long) =
        runTask(machineId, MachineStatus.STOPPED) { machine, cluster, node ->
            machine.vmid?.let {
                val upid =
                    when (kindOf(machine)) {
                        MachineKind.PROXMOX_LXC -> proxmoxClient.stopLxc(cluster, node, it)
                        MachineKind.PROXMOX_VM -> proxmoxClient.stopVm(cluster, node, it)
                    }
                proxmoxClient.waitForTask(cluster, upid, timeout())
            }
        }

    /** Async destroy (pct/qm per kind): DELETING -> torn down, IP released, and the row removed. */
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
                        proxmoxClient.waitForTask(
                            cluster,
                            proxmoxClient.destroyLxc(cluster, node, vmid),
                            timeout(),
                        )
                    // qm destroy refuses a running VM, so stop it first.
                    MachineKind.PROXMOX_VM ->
                        proxmoxClient.destroyVmGracefully(cluster, node, vmid, timeout())
                }
            }
            // AI teardown, independent of the machine's current aiMode (a switched machine still
            // holds BOTH a newapi token and a ccproxy registration): release the newapi token and
            // tear the machine down on ccproxy (frees its bound account). Both best-effort.
            runCatching { aiRegistry.forMode(AiMode.NEWAPI).teardown(machine) }
            machine.ccproxyMachineId?.let { id -> runCatching { ccproxyClient.deleteMachine(id) } }
            networkService.releaseIpsFor(machine.id!!)
            machineRepository.delete(machine) // soft delete
        } catch (e: Exception) {
            log.error("destroying machine {} failed: {}", machineId, e.message, e)
            machine.status = MachineStatus.ERROR
            machineRepository.save(machine)
        }
    }

    private fun timeout() = config.provisioning.taskTimeoutSeconds

    /** Run a pct action on the machine's CT, then land the given terminal status (or ERROR). */
    private fun runTask(
        machineId: Long,
        terminal: MachineStatus,
        action: (Machine, ProxmoxCluster, String) -> Unit,
    ) {
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
        try {
            val cluster = clusterOf(machine)
            val node = placementService.getPlacement(machine.placementId!!).node!!
            action(machine, cluster, node)
            machine.status = terminal
        } catch (e: Exception) {
            log.error("task on machine {} failed: {}", machineId, e.message, e)
            machine.status = MachineStatus.ERROR
        }
        machineRepository.save(machine)
    }

    /** Run init-machine.py inside the fresh container over SSH-as-root, if configured. */
    private fun runInit(machine: Machine, gateway: String, aiInitSuffix: String) {
        val command = config.provisioning.initCommand?.takeIf { it.isNotBlank() } ?: return
        if (operatorSsh.privateKeyPath() == null) return
        // `pct start` returning does NOT mean the guest is reachable — it's still booting (sshd not
        // up, network not ready). Wait until TCP :22 accepts a connection before SSHing in.
        operatorSsh.waitForSsh(machine.ip!!, config.provisioning.sshReadyTimeoutSeconds)
        val remote =
            command
                .replace("{user}", machine.loginUser ?: "")
                .replace("{sshPubkey}", machine.sshPubkey ?: "")
                .replace("{ip}", machine.ip ?: "")
                .replace("{gateway}", gateway) + aiInitSuffix
        operatorSsh.run("root", machine.ip!!, remote, config.provisioning.taskTimeoutSeconds)
        log.info("init-machine for machine {} succeeded", machine.id)
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return "Mc" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
