/*
 *  Description: The Network entity and repository. A network is an IPv4 range bound to a placement;
 *               machines provisioned into that placement draw their address from it. The range is
 *               stored as [startIp, endIp] inclusive, together with gateway / prefix / bridge.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.network

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class NetworkStatus {
    ACTIVE,
    DISABLED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "network", indexes = [Index(columnList = "placement_id")])
class Network(
    @Column(name = "placement_id", nullable = false) var placementId: IdType? = null,
    @Column var name: String? = null,
    @Column(name = "start_ip", nullable = false) var startIp: String? = null,
    @Column(name = "end_ip", nullable = false) var endIp: String? = null,
    @Column(nullable = false) var gateway: String? = null,
    @Column(name = "prefix_length", nullable = false) var prefixLength: Int? = null,
    @Column(nullable = false) var bridge: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: NetworkStatus = NetworkStatus.ACTIVE,
) : BaseEntity()

interface NetworkRepository : JpaRepository<Network, IdType> {
    fun findByPlacementId(placementId: IdType): List<Network>
}
