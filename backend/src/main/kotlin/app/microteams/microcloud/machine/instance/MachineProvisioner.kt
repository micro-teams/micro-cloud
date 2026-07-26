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
import app.microteams.microcloud.machine.network.NetworkService
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.proxmox.ProxmoxCluster
import app.microteams.microcloud.machine.proxmox.ProxmoxService
import app.microteams.microcloud.machine.template.TemplateUploadRepository
import app.microteams.microcloud.machine.template.TemplateUploadStatus
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
    private val machineRepository: MachineRepository,
) {
    private val log = LoggerFactory.getLogger(MachineProvisioner::class.java)
    private val random = SecureRandom()

    /** The Proxmox coordinates a machine lives at: cluster + node + vmid. */
    private fun clusterOf(machine: Machine): ProxmoxCluster =
        proxmoxService.getCluster(placementService.getPlacement(machine.placementId!!).clusterId!!)

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
            val ostemplate =
                templateUploadRepository
                    .findByTemplateIdAndPlacementId(machine.templateId!!, machine.placementId!!)
                    .filter { it.status == TemplateUploadStatus.DONE && it.volid != null }
                    .map { it.volid!! }
                    .orElseThrow {
                        IllegalStateException(
                            "template ${machine.templateId} is not uploaded to placement " +
                                "${machine.placementId}"
                        )
                    }

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
                operatorPublicKey()?.let { put("ssh-public-keys", it) }
                put("start", "1")
            }

            log.info("provisioning machine {} as {}/CT{}", machine.id, node, vmid)
            val upid = proxmoxClient.createLxc(cluster, node, params)
            proxmoxClient.waitForTask(cluster, upid, config.provisioning.taskTimeoutSeconds)
            machine.vmid = vmid
            machineRepository.save(machine)

            runInit(machine, network.gateway!!)

            machine.status = MachineStatus.RUNNING
            machineRepository.save(machine)
            log.info("machine {} is RUNNING (CT{})", machine.id, vmid)
        } catch (e: Exception) {
            log.error("provisioning machine {} failed: {}", machineId, e.message, e)
            machine.status = MachineStatus.ERROR
            machineRepository.save(machine)
        }
    }

    /** Async pct start: STARTING -> RUNNING / ERROR. */
    @Async
    @Transactional
    fun startCt(machineId: Long) =
        runTask(machineId, MachineStatus.RUNNING) { machine, cluster, node ->
            machine.vmid?.let {
                proxmoxClient.waitForTask(
                    cluster,
                    proxmoxClient.startLxc(cluster, node, it),
                    timeout(),
                )
            }
        }

    /** Async pct stop: STOPPING -> STOPPED / ERROR. */
    @Async
    @Transactional
    fun stopCt(machineId: Long) =
        runTask(machineId, MachineStatus.STOPPED) { machine, cluster, node ->
            machine.vmid?.let {
                proxmoxClient.waitForTask(
                    cluster,
                    proxmoxClient.stopLxc(cluster, node, it),
                    timeout(),
                )
            }
        }

    /** Async pct destroy: DELETING -> the CT is torn down, its IP released, and the row removed. */
    @Async
    @Transactional
    fun destroyCt(machineId: Long) {
        val machine = machineRepository.findById(machineId).orElse(null) ?: return
        try {
            machine.vmid?.let { vmid ->
                val cluster = clusterOf(machine)
                val node = placementService.getPlacement(machine.placementId!!).node!!
                proxmoxClient.waitForTask(
                    cluster,
                    proxmoxClient.destroyLxc(cluster, node, vmid),
                    timeout(),
                )
            }
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
    /**
     * The operator root public key injected into new containers: the configured value, or (default)
     * read from `${sshPrivateKeyPath}.pub`. Null when neither is available (no SSH init).
     */
    private fun operatorPublicKey(): String? {
        config.provisioning.rootSshPublicKey
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
        val keyPath =
            config.provisioning.sshPrivateKeyPath?.takeIf { it.isNotBlank() } ?: return null
        val pub = java.io.File("$keyPath.pub")
        return if (pub.isFile) pub.readText().trim().ifBlank { null } else null
    }

    private fun runInit(machine: Machine, gateway: String) {
        val command = config.provisioning.initCommand?.takeIf { it.isNotBlank() } ?: return
        val keyPath = config.provisioning.sshPrivateKeyPath?.takeIf { it.isNotBlank() } ?: return
        // `pct start` returning does NOT mean the guest is reachable — it's still booting (sshd not
        // up, network not ready). Wait until TCP :22 accepts a connection before SSHing in.
        waitForSsh(machine.ip!!, config.provisioning.sshReadyTimeoutSeconds)
        val remote =
            command
                .replace("{user}", machine.loginUser ?: "")
                .replace("{sshPubkey}", machine.sshPubkey ?: "")
                .replace("{ip}", machine.ip ?: "")
                .replace("{gateway}", gateway)
        val process =
            ProcessBuilder(
                    "ssh",
                    "-i",
                    keyPath,
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "-o",
                    "ConnectTimeout=20",
                    "root@${machine.ip}",
                    remote,
                )
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) throw IllegalStateException("init-machine over SSH failed ($code): $output")
        log.info("init-machine for machine {} succeeded", machine.id)
    }

    /** Poll TCP :22 on the machine until it accepts a connection (the guest has booted enough). */
    private fun waitForSsh(ip: String, timeoutSeconds: Long) {
        var waited = 0L
        while (waited < timeoutSeconds) {
            try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, 22), 3000) }
                return
            } catch (e: Exception) {
                Thread.sleep(3000)
                waited += 3
            }
        }
        throw IllegalStateException("$ip did not become SSH-reachable within ${timeoutSeconds}s")
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return "Mc" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
