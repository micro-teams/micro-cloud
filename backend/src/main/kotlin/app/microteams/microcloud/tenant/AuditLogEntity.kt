/*
 *  Description: An audit record of one action taken with a tenant auth secret. Every
 *               secret-authenticated request writes one (see authz/SecretAuthFilter).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.tenant

import jakarta.persistence.*
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "audit_log",
    indexes = [Index(columnList = "tenant_id"), Index(columnList = "secret_id")],
)
class AuditLog(
    @Column(name = "tenant_id") var tenantId: IdType? = null,
    @Column(name = "secret_id") var secretId: IdType? = null,
    @Column(nullable = false, length = 512) var action: String? = null,
) : BaseEntity()

interface AuditLogRepository : JpaRepository<AuditLog, IdType>
