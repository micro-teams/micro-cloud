/*
 *  Description: The super-admin login endpoint: exchange the platform operator's password for a
 *               short-lived session JWT carrying the super-admin permission set.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.superadmin

import app.microteams.microcloud.api.SuperadminApi
import app.microteams.microcloud.authz.InvalidCredentialsError
import app.microteams.microcloud.authz.ROLE_SUPER_ADMIN
import app.microteams.microcloud.authz.TokenService
import app.microteams.microcloud.common.config.MicroCloudConfig
import app.microteams.microcloud.model.SuperadminLoginRequestDTO
import app.microteams.microcloud.model.SuperadminLoginResponseDTO
import java.security.MessageDigest
import org.rucca.cheese.auth.annotation.NoAuth
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class SuperadminController(
    private val config: MicroCloudConfig,
    private val tokenService: TokenService,
) : SuperadminApi {
    @NoAuth
    override fun superadminLogin(
        superadminLoginRequestDTO: SuperadminLoginRequestDTO
    ): ResponseEntity<SuperadminLoginResponseDTO> {
        if (!constantTimeEquals(superadminLoginRequestDTO.password, config.superadminPassword)) {
            throw InvalidCredentialsError()
        }
        val minted = tokenService.mint(config.superadminId, ROLE_SUPER_ADMIN)
        return ResponseEntity.ok(
            SuperadminLoginResponseDTO(token = minted.token, expiresAt = minted.expiresAt)
        )
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
