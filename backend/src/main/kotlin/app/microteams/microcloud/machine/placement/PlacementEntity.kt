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
    @Column(nullable = false) var name: String? = null,
    @Column(name = "cluster_id", nullable = false) var clusterId: IdType? = null,
    @Column(nullable = false) var node: String? = null,
    @Column(nullable = false) var pool: String? = null,
    @Column(nullable = false) var storage: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PlacementStatus = PlacementStatus.ACTIVE,
) : BaseEntity()

interface PlacementRepository : JpaRepository<Placement, IdType> {
    fun findByClusterId(clusterId: IdType): List<Placement>
}
