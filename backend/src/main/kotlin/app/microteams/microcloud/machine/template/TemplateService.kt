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
import app.microteams.microcloud.machine.MachineKind
import app.microteams.microcloud.machine.placement.PlacementService
import app.microteams.microcloud.machine.placement.effectiveKind
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
        kind = this.kind.wire,
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
    private val catalogWriter: TemplateCatalogWriter,
    private val config: MicroCloudConfig,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(TemplateService::class.java)

    /** Discover templates from the templates directory on startup (see syncFromDir). */
    @PostConstruct
    fun seedCatalog() {
        syncFromDir()
    }

    /**
     * Enumerate the templates directory and upsert a catalog entry per template descriptor found. A
     * template's name is its directory, its kind is that directory's parent (`lxc` / `vm`), and its
     * `source` is what the uploader consumes:
     * - **LXC** — a rootfs image `*.tar.zst`; `source` is the image's path (uploaded as a vztmpl).
     * - **VM** — a text file named `image-url` holding the base cloud image's http(s) URL; `source`
     *   is that URL (the uploader downloads it, then bakes a VM template — see [TemplateUploader]).
     *
     * So an operator just drops a template into the directory and it appears in the catalog — no
     * config needed. Non-descriptor files (build scripts, READMEs) are ignored.
     */
    fun syncFromDir() {
        val root = java.io.File(config.templatesDir)
        if (!root.isDirectory) return
        root
            .walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val name = file.parentFile?.name ?: file.nameWithoutExtension
                // The kind directory (grandparent) maps to a Proxmox MachineKind: templates/lxc/…
                // ->
                // proxmox/lxc, templates/vm/… -> proxmox/vm. (Proxmox is the only provider today; a
                // future provider would extend the layout + this mapping.)
                val kind =
                    when (file.parentFile?.parentFile?.name?.lowercase()) {
                        "vm" -> MachineKind.PROXMOX_VM
                        else -> MachineKind.PROXMOX_LXC
                    }
                val source =
                    when {
                        file.name.endsWith(".tar.zst") -> file.absolutePath
                        file.name == "image-url" -> file.readText().trim().ifBlank { null }
                        else -> null
                    } ?: return@forEach
                // Each template is written in its own transaction; a single failure is logged and
                // skipped, never aborting the scan or crashing startup.
                try {
                    catalogWriter.upsert(name, kind, source)
                } catch (e: Exception) {
                    log.warn("skipping template {}/{}: {}", kind, name, e.message)
                }
            }
    }

    fun getTemplate(id: IdType): MachineTemplate =
        templateRepository.findById(id).orElseThrow { NotFoundError("machine-template", id) }

    fun listTemplates(pageStart: IdType?, pageSize: Int): Pair<List<MachineTemplateDTO>, PageDTO> {
        syncFromDir() // reflect any templates added to the directory since startup
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
        val template = getTemplate(templateId) // 404 guard
        val placement = placementService.getPlacement(placementId) // 404 guard
        // A template can only be uploaded to a placement that hosts its kind.
        if (template.kind != placement.effectiveKind)
            throw org.rucca.cheese.common.error.BadRequestError(
                "template ${template.kind.wire} cannot be uploaded to a " +
                    "${placement.effectiveKind.wire} placement"
            )
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
