/*
 *  Description: The Placement entity and repository. A placement is a concrete landing coordinate for
 *               machines on a cluster: cluster + node + pool + storage. Networks bind to a placement,
 *               and machines are provisioned into one.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.placement

import app.microteams.microcloud.machine.MachineKind
import app.microteams.microcloud.machine.MachineKindConverter
import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class PlacementStatus {
    ACTIVE,
    DISABLED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "placement", indexes = [Index(columnList = "cluster_id"), Index(columnList = "name")])
class Placement(
    // The leading coordinate: which provider + machine form this spot hosts (proxmox/lxc,
    // proxmox/vm, …). It is the authoritative source for how a machine here is provisioned; the
    // provisioner dispatches on it. Nullable in Kotlin AND the DB so the column can be added to an
    // existing placement table without a backfill — a legacy row reads null, treated as PROXMOX_LXC
    // by [effectiveKind] (every placement created before this column existed was a Proxmox LXC
    // one).
    @Convert(converter = MachineKindConverter::class)
    @Column(name = "kind")
    var kind: MachineKind? = MachineKind.PROXMOX_LXC,
    @Column(nullable = false) var name: String? = null,
    @Column(name = "cluster_id", nullable = false) var clusterId: IdType? = null,
    @Column(nullable = false) var node: String? = null,
    @Column(nullable = false) var pool: String? = null,
    @Column(nullable = false) var storage: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PlacementStatus = PlacementStatus.ACTIVE,
) : BaseEntity()

/** The placement's kind, treating a legacy null (row predating the column) as PROXMOX_LXC. */
val Placement.effectiveKind: MachineKind
    get() = this.kind ?: MachineKind.PROXMOX_LXC

interface PlacementRepository : JpaRepository<Placement, IdType> {
    fun findByClusterId(clusterId: IdType): List<Placement>
}
