/*
 *  Description: The Offering entity and repository. An offering is a (machine type, zone, template)
 *               triple a tenant is allowed to use — the only "catalog" a tenant sees. Super-admin
 *               composes them per tenant; a machine is created from one.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.offering

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class OfferingStatus {
    ACTIVE,
    DISABLED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "offering", indexes = [Index(columnList = "tenant_id")])
class Offering(
    @Column(name = "tenant_id", nullable = false) var tenantId: IdType? = null,
    @Column(name = "machine_type_id", nullable = false) var machineTypeId: IdType? = null,
    @Column(name = "zone_id", nullable = false) var zoneId: IdType? = null,
    @Column(name = "template_id", nullable = false) var templateId: IdType? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OfferingStatus = OfferingStatus.ACTIVE,
) : BaseEntity()

interface OfferingRepository : JpaRepository<Offering, IdType> {
    fun findByTenantId(tenantId: IdType): List<Offering>
}
