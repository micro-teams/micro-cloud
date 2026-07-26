/*
 *  Description: The IpAllocation entity and repository — one leased IPv4 address from a network,
 *               bound to the machine that holds it. Uniqueness on (network_id, ip) prevents a double
 *               lease; the machine binding lets an address be released when its machine is destroyed.
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

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "ip_allocation",
    indexes =
        [
            Index(name = "idx_ip_alloc_net_ip", columnList = "network_id, ip", unique = true),
            Index(columnList = "machine_id"),
        ],
)
class IpAllocation(
    @Column(name = "network_id", nullable = false) var networkId: IdType? = null,
    @Column(name = "machine_id", nullable = false) var machineId: IdType? = null,
    @Column(nullable = false) var ip: String? = null,
) : BaseEntity()

interface IpAllocationRepository : JpaRepository<IpAllocation, IdType> {
    fun findByNetworkId(networkId: IdType): List<IpAllocation>

    fun findByMachineId(machineId: IdType): List<IpAllocation>

    fun countByNetworkId(networkId: IdType): Long
}
