/*
 *  Description: The ProxmoxCluster entity and repository — a Proxmox provider credential (API URL +
 *               API token). The token secret is stored for runtime use only and is never returned by
 *               the API. A cluster is the root of the placement/network hierarchy machines land on.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.proxmox

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class ProxmoxClusterStatus {
    ACTIVE,
    DISABLED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "proxmox_cluster", indexes = [Index(columnList = "name")])
class ProxmoxCluster(
    @Column(nullable = false) var name: String? = null,
    @Column(name = "api_url", nullable = false) var apiUrl: String? = null,
    @Column(name = "token_id", nullable = false) var tokenId: String? = null,
    // The Proxmox API token secret: write-only, used only at runtime, never serialized back out.
    @Column(name = "token_secret", nullable = false) var tokenSecret: String? = null,
    @Column(name = "verify_tls", nullable = false) var verifyTls: Boolean = true,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ProxmoxClusterStatus = ProxmoxClusterStatus.ACTIVE,
) : BaseEntity()

interface ProxmoxClusterRepository : JpaRepository<ProxmoxCluster, IdType>
