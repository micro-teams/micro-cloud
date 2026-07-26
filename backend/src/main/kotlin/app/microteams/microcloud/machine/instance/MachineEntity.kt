/*
 *  Description: The Machine entity and repository — a provisioned instance owned by a tenant's
 *               customer, charged to one of that customer's fund accounts. It records the tenant view
 *               (type / zone / template / spec / status / private IP) plus the internal provisioning
 *               coordinates (placement, network, Proxmox vmid, login user, ssh key) the worker needs.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.instance

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class MachineStatus {
    PROVISIONING,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    DELETING,
    DELETED,
    ERROR,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "machine",
    indexes = [Index(columnList = "tenant_id"), Index(columnList = "tenant_id, customer_id")],
)
class Machine(
    @Column(name = "tenant_id", nullable = false) var tenantId: IdType? = null,
    @Column(name = "customer_id", nullable = false) var customerId: IdType? = null,
    @Column(name = "account_id", nullable = false) var accountId: IdType? = null,
    @Column(nullable = false) var hostname: String? = null,
    @Column(name = "offering_id", nullable = false) var offeringId: IdType? = null,
    @Column(name = "type_id", nullable = false) var typeId: IdType? = null,
    @Column(name = "zone_id") var zoneId: IdType? = null,
    @Column(name = "template_id", nullable = false) var templateId: IdType? = null,
    @Column(name = "api_key_id") var apiKeyId: IdType? = null,
    @Column(nullable = false) var cores: Int? = null,
    @Column(name = "memory_mb", nullable = false) var memoryMb: Int? = null,
    @Column(name = "disk_gb", nullable = false) var diskGb: Int? = null,
    // Internal provisioning coordinates (not exposed to tenants).
    @Column(name = "placement_id", nullable = false) var placementId: IdType? = null,
    @Column(name = "network_id", nullable = false) var networkId: IdType? = null,
    @Column(name = "vmid") var vmid: Int? = null,
    @Column(name = "login_user", nullable = false) var loginUser: String? = null,
    @Column(name = "ssh_pubkey", length = 4096) var sshPubkey: String? = null,
    @Column var ip: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: MachineStatus = MachineStatus.PROVISIONING,
) : BaseEntity()

interface MachineRepository : JpaRepository<Machine, IdType> {
    fun findByTenantId(tenantId: IdType): List<Machine>

    fun findByTenantIdAndCustomerId(tenantId: IdType, customerId: IdType): List<Machine>
}
