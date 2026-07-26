/*
 *  Description: The Tenant and AuthSecret entities and their repositories. A tenant is one upstream
 *               deployment; it authenticates with one of several auth secrets (stored only as a
 *               SHA-256 hash) and owns customers/accounts/machines.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.tenant

import jakarta.persistence.*
import java.util.Optional
import org.hibernate.annotations.SQLRestriction
import org.rucca.cheese.common.persistent.BaseEntity
import org.rucca.cheese.common.persistent.IdType
import org.springframework.data.jpa.repository.JpaRepository

enum class TenantStatus {
    ACTIVE,
    SUSPENDED,
}

enum class AuthSecretStatus {
    ACTIVE,
    REVOKED,
}

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "tenant", indexes = [Index(columnList = "name")])
class Tenant(
    @Column(nullable = false) var name: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TenantStatus = TenantStatus.ACTIVE,
) : BaseEntity()

interface TenantRepository : JpaRepository<Tenant, IdType>

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(
    name = "auth_secret",
    indexes =
        [
            Index(columnList = "tenant_id"),
            Index(name = "idx_secret_hash", columnList = "secret_hash", unique = true),
        ],
)
class AuthSecret(
    @Column(name = "tenant_id", nullable = false) var tenantId: IdType? = null,
    @Column(name = "secret_hash", nullable = false, length = 64) var secretHash: String? = null,
    @Column var label: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AuthSecretStatus = AuthSecretStatus.ACTIVE,
) : BaseEntity()

interface AuthSecretRepository : JpaRepository<AuthSecret, IdType> {
    fun findByTenantId(tenantId: IdType): List<AuthSecret>

    fun findBySecretHashAndStatus(
        secretHash: String,
        status: AuthSecretStatus,
    ): Optional<AuthSecret>
}
