/*
 *  Description: Template catalog + per-placement upload tracking. Templates are seeded from config on
 *               startup (built at build time; the API is list-only). Uploading (super-admin) records
 *               a per-placement upload row and returns it for polling; the actual image import into
 *               Proxmox storage runs asynchronously (worker TODO) and updates the row's status.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.template

import app.microteams.microcloud.common.config.MicroCloudConfig
import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.model.*
import jakarta.annotation.PostConstruct
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

fun MachineTemplate.toDTO() =
    MachineTemplateDTO(
        id = this.id!!,
        name = this.name!!,
        description = this.description,
        kind =
            when (this.kind) {
                MachineTemplateKind.LXC -> MachineTemplateDTO.Kind.lxc
                MachineTemplateKind.VM -> MachineTemplateDTO.Kind.vm
            },
        status =
            when (this.status) {
                MachineTemplateStatus.ACTIVE -> MachineTemplateStatusDTO.active
                MachineTemplateStatus.DISABLED -> MachineTemplateStatusDTO.disabled
            },
        createdAt = this.createdAt?.atOffset(java.time.ZoneOffset.UTC),
    )

fun TemplateUpload.toDTO() =
    TemplateUploadDTO(
        id = this.id!!,
        templateId = this.templateId!!,
        placementId = this.placementId!!,
        status =
            when (this.status) {
                TemplateUploadStatus.PENDING -> TemplateUploadStatusDTO.pending
                TemplateUploadStatus.UPLOADING -> TemplateUploadStatusDTO.uploading
                TemplateUploadStatus.DONE -> TemplateUploadStatusDTO.done
                TemplateUploadStatus.ERROR -> TemplateUploadStatusDTO.error
            },
        volid = this.volid,
        jobLog = this.jobLog,
        updatedAt = this.updatedAt?.atOffset(java.time.ZoneOffset.UTC),
    )

@Service
@Transactional
class TemplateService(
    private val templateRepository: MachineTemplateRepository,
    private val uploadRepository: TemplateUploadRepository,
    private val placementService: PlacementService,
    private val templateUploader: TemplateUploader,
    private val config: MicroCloudConfig,
) {
    /** Seed / upsert the configured template catalog by name on startup. */
    @PostConstruct
    fun seedCatalog() {
        config.templates.forEach { entry ->
            val template =
                templateRepository.findByName(entry.name).orElseGet {
                    MachineTemplate(name = entry.name)
                }
            template.description = entry.description
            template.source = entry.source
            template.kind =
                when (entry.kind.lowercase()) {
                    "vm" -> MachineTemplateKind.VM
                    else -> MachineTemplateKind.LXC
                }
            templateRepository.save(template)
        }
    }

    fun getTemplate(id: IdType): MachineTemplate =
        templateRepository.findById(id).orElseThrow { NotFoundError("machine-template", id) }

    fun listTemplates(pageStart: IdType?, pageSize: Int): Pair<List<MachineTemplateDTO>, PageDTO> {
        val all = templateRepository.findAll().sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun listUploads(
        templateId: IdType,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<TemplateUploadDTO>, PageDTO> {
        getTemplate(templateId) // 404 guard
        val all = uploadRepository.findByTemplateId(templateId).sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    /**
     * Start (or restart) uploading a template's image to a placement's storage. Upserts the
     * per-(template, placement) row to PENDING and returns it; the async import worker picks it up
     * and drives it to DONE/ERROR. Idempotent per (template, placement).
     */
    fun startUpload(templateId: IdType, placementId: IdType): TemplateUploadDTO {
        getTemplate(templateId) // 404 guard
        placementService.getPlacement(placementId) // 404 guard
        val upload =
            uploadRepository.findByTemplateIdAndPlacementId(templateId, placementId).orElseGet {
                TemplateUpload(templateId = templateId, placementId = placementId)
            }
        upload.status = TemplateUploadStatus.PENDING
        upload.volid = null
        upload.jobLog = null
        val saved = uploadRepository.save(upload)
        // Run the actual Proxmox image import after this transaction commits (so the async worker
        // sees the row); it flips status UPLOADING -> DONE / ERROR and records the volid.
        afterCommit { templateUploader.runUpload(saved.id!!) }
        return saved.toDTO()
    }

    /** Run [action] after the current transaction commits, or immediately if none is active. */
    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = action()
                }
            )
        } else {
            action()
        }
    }
}
