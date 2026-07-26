/*
 *  Description: Uploads a template's image onto a placement's storage on Proxmox, so a machine can be
 *               created from it. Runs asynchronously: it picks a vztmpl-capable storage on the
 *               placement's node, then either has Proxmox download the image from the template's URL
 *               or streams a local file up via multipart, waits for the task, and records the
 *               resulting volid on the upload row — driving it UPLOADING -> DONE / ERROR.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.template

import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.proxmox.ProxmoxService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class TemplateUploader(
    private val proxmoxService: ProxmoxService,
    private val placementService: PlacementService,
    private val templateRepository: MachineTemplateRepository,
    private val uploadRepository: TemplateUploadRepository,
    private val proxmoxClient: ProxmoxClient,
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
            upload.status = TemplateUploadStatus.DONE
            uploadRepository.save(upload)
            log.info("template {} uploaded as {}", template.id, upload.volid)
        } catch (e: Exception) {
            log.error("uploading template for upload {} failed: {}", uploadId, e.message, e)
            upload.status = TemplateUploadStatus.ERROR
            upload.jobLog = e.message?.take(4000)
            uploadRepository.save(upload)
        }
    }
}
