/*
 *  Description: Makes a template usable on a placement, asynchronously, driving its upload row
 *               UPLOADING -> DONE / ERROR. Branches on the template's kind:
 *                 - LXC: picks a vztmpl-capable storage on the placement's node and either has
 *                   Proxmox download the rootfs image from the template's URL or streams a local file
 *                   up, then records the resulting vztmpl volid.
 *                 - VM:  "bakes" a Proxmox VM template — downloads the base cloud image, boots a
 *                   throwaway VM from it with a cloud-init operator key + a leased temp IP, SSHes in
 *                   to run the template's build.sh (installs Docker, puts the cloud-init user in the
 *                   docker group), powers it off and runs `qm template`, then records the template's
 *                   vmid. The temp IP is released and no per-machine SSH init is needed afterwards:
 *                   clones inject the login user / key / static IP via cloud-init.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.template

import app.microteams.microcloud.common.config.MicroCloudConfig
import app.microteams.microcloud.machine.network.NetworkService
import app.microteams.microcloud.machine.placement.Placement
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.machine.proxmox.OperatorSsh
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.proxmox.ProxmoxCluster
import app.microteams.microcloud.machine.proxmox.ProxmoxService
import java.io.File
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class TemplateUploader(
    private val proxmoxService: ProxmoxService,
    private val placementService: PlacementService,
    private val networkService: NetworkService,
    private val templateRepository: MachineTemplateRepository,
    private val uploadRepository: TemplateUploadRepository,
    private val proxmoxClient: ProxmoxClient,
    private val operatorSsh: OperatorSsh,
    private val config: MicroCloudConfig,
) {
    private val log = LoggerFactory.getLogger(TemplateUploader::class.java)

    @Async
    @Transactional
    fun runUpload(uploadId: Long) {
        val upload = uploadRepository.findById(uploadId).orElse(null) ?: return
        try {
            upload.status = TemplateUploadStatus.UPLOADING
            uploadRepository.save(upload)

            val template =
                templateRepository.findById(upload.templateId!!).orElseThrow {
                    IllegalStateException("template ${upload.templateId} vanished")
                }
            val source =
                template.source
                    ?: throw IllegalStateException(
                        "template ${template.id} has no source image to upload"
                    )
            val placement = placementService.getPlacement(upload.placementId!!)
            val cluster = proxmoxService.getCluster(placement.clusterId!!)

            when (template.kind) {
                MachineTemplateKind.LXC -> uploadLxc(upload, template, source, placement, cluster)
                MachineTemplateKind.VM -> bakeVm(upload, template, source, placement, cluster)
            }

            upload.status = TemplateUploadStatus.DONE
            uploadRepository.save(upload)
            log.info("template {} ready on placement {}", template.id, placement.id)
        } catch (e: Exception) {
            log.error("preparing template for upload {} failed: {}", uploadId, e.message, e)
            upload.status = TemplateUploadStatus.ERROR
            upload.jobLog = e.message?.take(4000)
            uploadRepository.save(upload)
        }
    }

    /** LXC: put the rootfs tarball on a vztmpl storage and record its volid. */
    private fun uploadLxc(
        upload: TemplateUpload,
        template: MachineTemplate,
        source: String,
        placement: Placement,
        cluster: ProxmoxCluster,
    ) {
        val node = placement.node!!
        // Prefer the placement's storage if it accepts templates, else any vztmpl storage.
        val vztmplStorages = proxmoxClient.vztmplStorages(cluster, node)
        val storage =
            vztmplStorages.firstOrNull { it == placement.storage }
                ?: vztmplStorages.firstOrNull()
                ?: throw IllegalStateException("node $node has no vztmpl-capable storage")

        val filename = "${template.name}.tar.zst"
        log.info("uploading template {} to {}:{} on {}", template.id, storage, filename, node)
        val upid =
            if (source.startsWith("http://") || source.startsWith("https://"))
                proxmoxClient.downloadTemplateFromUrl(cluster, node, storage, filename, source)
            else proxmoxClient.uploadTemplateFile(cluster, node, storage, filename, source)
        if (upid.isNotBlank()) proxmoxClient.waitForTask(cluster, upid, 1800)

        upload.volid = "$storage:vztmpl/$filename"
    }

    /**
     * VM: bake a Proxmox VM template on the placement. Downloads the base cloud image, boots a
     * throwaway VM from it (cloud-init operator user + key + a leased temp IP), runs the template's
     * build.sh over SSH, powers off, `qm template`s it, and records the template vmid. The temp IP
     * is always released. Mirrors the operator's manual flow (create → boot → apt docker + docker
     * group → shutdown → template).
     */
    private fun bakeVm(
        upload: TemplateUpload,
        template: MachineTemplate,
        source: String,
        placement: Placement,
        cluster: ProxmoxCluster,
    ) {
        val node = placement.node!!
        val buildScript = File("${config.templatesDir}/vm/${template.name}/build.sh")
        if (!buildScript.isFile)
            throw IllegalStateException("VM template ${template.name} has no build.sh")
        val operatorKey =
            operatorSsh.publicKey()
                ?: throw IllegalStateException("no operator SSH public key to bake a VM template")

        // 1. Download the base cloud image into an import-capable storage.
        val importStorage =
            proxmoxClient.importStorages(cluster, node).firstOrNull()
                ?: throw IllegalStateException("node $node has no import-capable storage")
        val imageName = "${template.name}-base.qcow2"
        log.info(
            "downloading VM base image for {} to {}:import on {}",
            template.id,
            importStorage,
            node,
        )
        val dlUpid =
            proxmoxClient.downloadImportImage(cluster, node, importStorage, imageName, source)
        if (dlUpid.isNotBlank())
            proxmoxClient.waitForTask(cluster, dlUpid, config.provisioning.vmBakeTimeoutSeconds)

        // 2. Lease a temp IP for the throwaway bake VM (a synthetic negative "machine id" that will
        //    never collide with a real machine, released in the finally).
        val network =
            networkService.networksWithFreeIp(placement.id!!).firstOrNull()
                ?: throw IllegalStateException(
                    "placement ${placement.id} has no network with a free IP to bake a VM template"
                )
        val bakeLeaseId = -(upload.id!!)
        val ip = networkService.allocateIp(network.id!!, bakeLeaseId)
        val prov = config.provisioning
        var bakeVmid: Int? = null
        try {
            // 3. Create the throwaway VM importing the base image, with cloud-init operator access.
            val vmid = proxmoxClient.nextVmid(cluster)
            bakeVmid = vmid
            val createParams = buildMap {
                put("vmid", vmid.toString())
                put("name", "mc-bake-${template.name}")
                put("cores", prov.vmBakeCores.toString())
                put("memory", prov.vmBakeMemoryMb.toString())
                put("cpu", "host")
                put("scsihw", "virtio-scsi-single")
                put("scsi0", "${placement.storage}:0,import-from=$importStorage:import/$imageName")
                put("ide2", "${placement.storage}:cloudinit")
                put("boot", "order=scsi0")
                put("serial0", "socket")
                put("vga", "serial0")
                put("ostype", "l26")
                put("net0", "virtio,bridge=${network.bridge}")
                put("pool", placement.pool!!)
                put("ciuser", prov.vmBakeUser)
                put("sshkeys", proxmoxClient.sshkeysParam(operatorKey))
                put("ipconfig0", "ip=$ip/${network.prefixLength},gw=${network.gateway}")
            }
            log.info("baking VM template {} on {}/VM{}", template.id, node, vmid)
            proxmoxClient.waitForTask(
                cluster,
                proxmoxClient.createVm(cluster, node, createParams),
                prov.taskTimeoutSeconds,
            )

            // 4. Boot it, wait for SSH, run build.sh, then power off cleanly.
            proxmoxClient.waitForTask(
                cluster,
                proxmoxClient.startVm(cluster, node, vmid),
                prov.taskTimeoutSeconds,
            )
            operatorSsh.waitForSsh(ip, prov.sshReadyTimeoutSeconds)
            operatorSsh.runScript(
                prov.vmBakeUser,
                ip,
                buildScript,
                "sudo bash -s",
                prov.vmBakeTimeoutSeconds,
            )
            proxmoxClient.waitForTask(
                cluster,
                proxmoxClient.stopVm(cluster, node, vmid),
                prov.taskTimeoutSeconds,
            )
            waitForVmStopped(cluster, node, vmid, prov.taskTimeoutSeconds)

            // 5. Convert to a template and record its vmid.
            proxmoxClient.waitForTask(
                cluster,
                proxmoxClient.templateVm(cluster, node, vmid),
                prov.taskTimeoutSeconds,
            )
            upload.templateVmid = vmid
            bakeVmid = null // succeeded — keep it as the template, don't destroy it
            log.info("VM template {} baked as VM{}", template.id, vmid)
        } catch (e: Exception) {
            // On failure, tear the throwaway VM down so a retry starts clean. It is likely still
            // running, and qm destroy refuses a running VM, so stop-then-destroy.
            bakeVmid?.let {
                runCatching {
                    proxmoxClient.destroyVmGracefully(cluster, node, it, prov.taskTimeoutSeconds)
                }
            }
            throw e
        } finally {
            networkService.releaseIpsFor(bakeLeaseId)
        }
    }

    private fun waitForVmStopped(
        cluster: ProxmoxCluster,
        node: String,
        vmid: Int,
        timeoutSeconds: Long,
    ) {
        var waited = 0L
        while (waited < timeoutSeconds) {
            if (proxmoxClient.vmStatus(cluster, node, vmid) == "stopped") return
            Thread.sleep(2000)
            waited += 2
        }
        throw IllegalStateException("VM$vmid did not stop within ${timeoutSeconds}s")
    }
}
