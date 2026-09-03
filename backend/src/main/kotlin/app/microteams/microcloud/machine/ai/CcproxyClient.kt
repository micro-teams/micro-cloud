/*
 *  Description: A thin client for the ccproxy tenant API. ccproxy is an EXTERNAL service; MicroCloud
 *               is one of its tenants (opaque bearer secret). MicroCloud uses it to (birth-)register a
 *               machine, read the operator SSH public key it must authorize on the machine, trigger a
 *               machine's subscription login, poll its status, and tear it down. ccproxy itself does
 *               all the on-machine work (CA, proxy env in ~/.claude/settings.json, OAuth via tmux,
 *               and the flip to the official endpoint); this client only drives that lifecycle.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.common.config.MicroCloudConfig
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.rucca.cheese.common.error.BadRequestError
import org.springframework.stereotype.Component

/** The login ccproxy started for a machine: its id (what a cancel needs) and the account bound. */
data class CcproxyLoginRequest(val id: Long?, val status: String, val accountEmail: String?)

/** A machine as ccproxy sees it (the subset MicroCloud drives its switch on). */
data class CcproxyMachine(
    val id: Long,
    val status: String,
    val hasCredential: Boolean,
    val credentialExpiresAt: Long?,
    val currentLoginRequestId: Long?,
    val error: String?,
)

@Component
class CcproxyClient(private val config: MicroCloudConfig, private val objectMapper: ObjectMapper) {
    private val cfg
        get() = config.ccproxy

    /** True when the operator wired ccproxy (base URL + tenant secret present). */
    fun isConfigured(): Boolean = !cfg.baseUrl.isNullOrBlank() && !cfg.tenantSecret.isNullOrBlank()

    /**
     * The operator SSH public key MicroCloud must authorize on every machine ccproxy will drive.
     */
    fun getSshPubkey(): String {
        val key = call("GET", "/provisioning/ssh-pubkey", null).path("publicKey").asText("")
        if (key.isBlank()) throw BadRequestError("ccproxy returned no operator ssh-pubkey")
        return key
    }

    /**
     * Birth-init: register the machine with ccproxy (SSH target = the login user @ its IP). ccproxy
     * binds NO account and only points the machine's Claude at the engine (unregistered → tunneled
     * straight through, no account consumed) until a later login. Returns the ccproxy machine.
     */
    fun createMachine(host: String, sshUser: String, sshPort: Int, label: String?): CcproxyMachine {
        val body =
            objectMapper.writeValueAsString(
                buildMap {
                    put("host", host)
                    put("sshUser", sshUser)
                    put("sshPort", sshPort)
                    if (label != null) put("label", label)
                }
            )
        return toMachine(call("POST", "/machine", body))
    }

    /** Read a machine's current state (status / credential / current login request). */
    fun getMachine(id: Long): CcproxyMachine = toMachine(call("GET", "/machine/$id", null))

    /**
     * Start (or restart) this machine's subscription login. ccproxy binds an account here (409 if
     * the pool is empty), registers the engine session, and drives the OAuth in a tmux session; a
     * human login-operator completes the browser step on ccproxy's side. 202 Accepted with the
     * LoginRequest it opened.
     */
    fun startLogin(id: Long): CcproxyLoginRequest {
        val data = call("POST", "/machine/$id/login", "")
        return CcproxyLoginRequest(
            id = data.path("id").let { if (it.isNumber) it.asLong() else null },
            status = data.path("status").asText(""),
            accountEmail = data.path("accountEmail").let { if (it.isTextual) it.asText() else null },
        )
    }

    /**
     * Cancel an in-progress login-request (e.g. a login the operator never completed), which frees
     * the machine from `loggingIn` back to `awaitingLogin` so a fresh login can start. Best-effort.
     */
    fun cancelLogin(loginRequestId: Long) {
        call("POST", "/login-request/$loginRequestId/cancel", "")
    }

    /** Tear the machine down on ccproxy: removes the engine session and frees the bound account. */
    fun deleteMachine(id: Long) {
        call("DELETE", "/machine/$id", null)
    }

    private fun toMachine(data: JsonNode): CcproxyMachine =
        CcproxyMachine(
            id = data.path("id").asLong(),
            status = data.path("status").asText(""),
            hasCredential = data.path("hasCredential").asBoolean(false),
            credentialExpiresAt =
                data.path("credentialExpiresAt").let { if (it.isNumber) it.asLong() else null },
            currentLoginRequestId =
                data.path("currentLoginRequestId").let { if (it.isNumber) it.asLong() else null },
            error = data.path("error").let { if (it.isTextual) it.asText() else null },
        )

    private fun call(method: String, path: String, body: String?): JsonNode {
        val base =
            (cfg.baseUrl ?: throw BadRequestError("microcloud.ccproxy.base-url is not set"))
                .trimEnd('/')
        val secret =
            cfg.tenantSecret?.takeIf { it.isNotBlank() }
                ?: throw BadRequestError("microcloud.ccproxy.tenant-secret is not set")
        val b =
            HttpRequest.newBuilder()
                .uri(URI.create("$base$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $secret")
                .method(
                    method,
                    if (body == null) HttpRequest.BodyPublishers.noBody()
                    else HttpRequest.BodyPublishers.ofString(body),
                )
        if (body != null) b.header("Content-Type", "application/json")
        val resp =
            try {
                client().send(b.build(), HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                throw BadRequestError("ccproxy request failed: ${e.message}")
            }
        if (resp.statusCode() !in 200..299)
            throw BadRequestError("ccproxy ${resp.statusCode()} for $method $path: ${resp.body()}")
        val raw = resp.body()
        return if (raw.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(raw)
    }

    private fun client(): HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
}
