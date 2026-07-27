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

import app.microteams.microcloud.machine.MachineKind
import app.microteams.microcloud.machine.MachineKindConverter
import jakarta.persistence.*
import java.util.Optional
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

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
    // Identity is (name, kind), NOT name alone: the same OS name exists once per kind (e.g. a
    // `debian13` LXC template AND a `debian13` VM template, laid out as templates/lxc/debian13 and
    // templates/vm/debian13). A name-only unique constraint would make the two collide — the dir
    // scan would clobber one with the other.
    indexes = [Index(name = "idx_template_name", columnList = "name")],
    uniqueConstraints =
        [UniqueConstraint(name = "uk_template_name_kind", columnNames = ["name", "kind"])],
)
class MachineTemplate(
    @Column(nullable = false) var name: String? = null,
    @Column var description: String? = null,
    // The image's kind (format + provider). A template can only be used on a placement of the same
    // kind. Stored as the wire string via the converter (legacy "LXC"/"VM" rows read fine).
    @Convert(converter = MachineKindConverter::class)
    @Column(nullable = false)
    var kind: MachineKind = MachineKind.PROXMOX_LXC,
    // Where the image is fetched from when uploading to a placement: a local file path or http(s)
    // URL.
    @Column(length = 1024) var source: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: MachineTemplateStatus = MachineTemplateStatus.ACTIVE,
) : BaseEntity()

interface MachineTemplateRepository : JpaRepository<MachineTemplate, IdType> {
    fun findByNameAndKind(name: String, kind: MachineKind): Optional<MachineTemplate>
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
    // How the uploaded template is referenced on this placement, per kind:
    //  - LXC: `volid` holds the vztmpl volume id (e.g. `local:vztmpl/debian13.tar.zst`).
    //  - VM:  `templateVmid` holds the id of the baked Proxmox VM template on the placement's node.
    @Column(name = "volid") var volid: String? = null,
    @Column(name = "template_vmid") var templateVmid: Int? = null,
    @Column(name = "job_log", length = 4096) var jobLog: String? = null,
) : BaseEntity()

interface TemplateUploadRepository : JpaRepository<TemplateUpload, IdType> {
    fun findByTemplateId(templateId: IdType): List<TemplateUpload>

    fun findByTemplateIdAndPlacementId(
        templateId: IdType,
        placementId: IdType,
    ): Optional<TemplateUpload>
}
