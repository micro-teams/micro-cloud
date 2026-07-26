/*
 *  Description: The MachineTemplate and TemplateUpload entities and their repositories. A template is
 *               a catalog image (built at build time, seeded on startup); tenants list it. A
 *               TemplateUpload tracks the state of one template's image on one placement's storage.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.template

import jakarta.persistence.*
import java.util.Optional
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class MachineTemplateKind {
    LXC,
    VM,
}

enum class MachineTemplateStatus {
    ACTIVE,
    DISABLED,
}

enum class TemplateUploadStatus {
    PENDING,
    UPLOADING,
    DONE,
    ERROR,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "machine_template",
    indexes = [Index(name = "idx_template_name", columnList = "name", unique = true)],
)
class MachineTemplate(
    @Column(nullable = false) var name: String? = null,
    @Column var description: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: MachineTemplateKind = MachineTemplateKind.LXC,
    // Where the image is fetched from when uploading to a placement: a local file path or http(s)
    // URL.
    @Column(length = 1024) var source: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: MachineTemplateStatus = MachineTemplateStatus.ACTIVE,
) : BaseEntity()

interface MachineTemplateRepository : JpaRepository<MachineTemplate, IdType> {
    fun findByName(name: String): Optional<MachineTemplate>
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "template_upload", indexes = [Index(columnList = "template_id, placement_id")])
class TemplateUpload(
    @Column(name = "template_id", nullable = false) var templateId: IdType? = null,
    @Column(name = "placement_id", nullable = false) var placementId: IdType? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TemplateUploadStatus = TemplateUploadStatus.PENDING,
    @Column(name = "volid") var volid: String? = null,
    @Column(name = "job_log", length = 4096) var jobLog: String? = null,
) : BaseEntity()

interface TemplateUploadRepository : JpaRepository<TemplateUpload, IdType> {
    fun findByTemplateId(templateId: IdType): List<TemplateUpload>

    fun findByTemplateIdAndPlacementId(
        templateId: IdType,
        placementId: IdType,
    ): Optional<TemplateUpload>
}
