/*
 *  Description: The Zone entity and repository. A zone is a locality partition over placements: two
 *               machines in the same zone communicate faster. Its backing placements are held as an
 *               element collection (admin config). Zones are tenant-visible; only admins write them.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.zone

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class ZoneStatus {
    ACTIVE,
    DISABLED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "zone", indexes = [Index(columnList = "name")])
class Zone(
    @Column(nullable = false) var name: String? = null,
    @Column var description: String? = null,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "zone_placement", joinColumns = [JoinColumn(name = "zone_id")])
    @Column(name = "placement_id")
    var placementIds: MutableList<IdType> = mutableListOf(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ZoneStatus = ZoneStatus.ACTIVE,
) : BaseEntity()

interface ZoneRepository : JpaRepository<Zone, IdType>
