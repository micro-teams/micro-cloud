/*
 *  Description: The MachineType entity and repository. A machine type is a performance class with
 *               allowed spec ranges (cores / memory / disk, min..max), backed by one or more
 *               placements. Tenant-visible; only admins write it.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.type

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class MachineTypeStatus {
    ACTIVE,
    DISABLED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "machine_type", indexes = [Index(columnList = "name")])
class MachineType(
    @Column(nullable = false) var name: String? = null,
    @Column var description: String? = null,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "machine_type_placement",
        joinColumns = [JoinColumn(name = "machine_type_id")],
    )
    @Column(name = "placement_id")
    var placementIds: MutableList<IdType> = mutableListOf(),
    @Column(name = "cores_min", nullable = false) var coresMin: Int? = null,
    @Column(name = "cores_max", nullable = false) var coresMax: Int? = null,
    @Column(name = "memory_mb_min", nullable = false) var memoryMbMin: Int? = null,
    @Column(name = "memory_mb_max", nullable = false) var memoryMbMax: Int? = null,
    @Column(name = "disk_gb_min", nullable = false) var diskGbMin: Int? = null,
    @Column(name = "disk_gb_max", nullable = false) var diskGbMax: Int? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: MachineTypeStatus = MachineTypeStatus.ACTIVE,
) : BaseEntity()

interface MachineTypeRepository : JpaRepository<MachineType, IdType>
