/*
 *  Description: 401 for a bad login credential.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.authz

import org.rucca.cheese.common.error.BaseError
import org.springframework.http.HttpStatus

class InvalidCredentialsError : BaseError(HttpStatus.UNAUTHORIZED, "invalid credentials")
