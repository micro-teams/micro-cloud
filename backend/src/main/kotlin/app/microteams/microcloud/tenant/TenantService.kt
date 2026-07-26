/*
 *  Description: CRUD for tenants and their auth secrets. Secrets are returned in plaintext only once
 *               (on creation) and stored only as a SHA-256 hash, looked up on each authenticated
 *               tenant request.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.tenant

import app.microteams.microcloud.common.helper.PageHelper
import app.microteams.microcloud.model.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.rucca.cheese.common.error.NotFoundError
import org.rucca.cheese.common.persistent.IdType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

fun Tenant.toDTO() =
    TenantDTO(
        id = this.id!!,
        name = this.name!!,
        status = this.status.toDTO(),
        createdAt = this.createdAt?.atOffset(ZoneOffset.UTC),
    )

fun TenantStatus.toDTO() =
    when (this) {
        TenantStatus.ACTIVE -> TenantStatusDTO.active
        TenantStatus.SUSPENDED -> TenantStatusDTO.suspended
    }

fun TenantStatusDTO.toEntity() =
    when (this) {
        TenantStatusDTO.active -> TenantStatus.ACTIVE
        TenantStatusDTO.suspended -> TenantStatus.SUSPENDED
    }

fun AuthSecret.toDTO() =
    TenantSecretDTO(
        id = this.id!!,
        tenantId = this.tenantId!!,
        label = this.label,
        status =
            when (this.status) {
                AuthSecretStatus.ACTIVE -> TenantSecretStatusDTO.active
                AuthSecretStatus.REVOKED -> TenantSecretStatusDTO.revoked
            },
        createdAt = this.createdAt?.atOffset(ZoneOffset.UTC),
        lastUsedAt = null,
    )

@Service
@Transactional
class TenantService(
    private val tenantRepository: TenantRepository,
    private val authSecretRepository: AuthSecretRepository,
) {
    fun getTenant(id: IdType): Tenant =
        tenantRepository.findById(id).orElseThrow { NotFoundError("tenant", id) }

    fun getTenantDTO(id: IdType): TenantDTO = getTenant(id).toDTO()

    fun createTenant(name: String): TenantDTO = tenantRepository.save(Tenant(name = name)).toDTO()

    fun listTenants(pageStart: IdType?, pageSize: Int): Pair<List<TenantDTO>, PageDTO> {
        val all = tenantRepository.findAll().sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun updateTenant(id: IdType, name: String?, status: TenantStatusDTO?): TenantDTO {
        val tenant = getTenant(id)
        name?.let { tenant.name = it }
        status?.let { tenant.status = it.toEntity() }
        return tenantRepository.save(tenant).toDTO()
    }

    fun deleteTenant(id: IdType) {
        val tenant = getTenant(id)
        tenant.deletedAt = LocalDateTime.now()
        tenantRepository.save(tenant)
    }

    fun listSecrets(
        tenantId: IdType,
        pageStart: IdType?,
        pageSize: Int,
    ): Pair<List<TenantSecretDTO>, PageDTO> {
        getTenant(tenantId) // 404 if the tenant does not exist
        val all = authSecretRepository.findByTenantId(tenantId).sortedBy { it.id }
        val (page, info) = PageHelper.pageFromAll(all, pageStart, pageSize, { it.id!! }, null)
        return page.map { it.toDTO() } to info
    }

    fun createSecret(tenantId: IdType, label: String?): CreateTenantSecretResponseDTO {
        getTenant(tenantId)
        val secret = randomSecret()
        val saved =
            authSecretRepository.save(
                AuthSecret(tenantId = tenantId, secretHash = sha256Hex(secret), label = label)
            )
        return CreateTenantSecretResponseDTO(
            id = saved.id!!,
            tenantId = tenantId,
            label = label,
            secret = secret,
            createdAt = saved.createdAt?.atOffset(ZoneOffset.UTC),
        )
    }

    fun revokeSecret(tenantId: IdType, secretId: IdType) {
        val secret =
            authSecretRepository.findById(secretId).orElseThrow {
                NotFoundError("auth_secret", secretId)
            }
        if (secret.tenantId != tenantId) throw NotFoundError("auth_secret", secretId)
        secret.status = AuthSecretStatus.REVOKED
        secret.deletedAt = LocalDateTime.now()
        authSecretRepository.save(secret)
    }

    private fun randomSecret(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rng = SecureRandom()
        return (1..40).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
