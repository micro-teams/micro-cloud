/*
 * Description: Exercise guest identity checks against a real HTTP endpoint, including ID reuse.
 * Author(s):
 *     Zhifei Li <andylizf@outlook.com>
 */
package app.microteams.microcloud.api

import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.proxmox.ProxmoxCluster
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.rucca.cheese.common.error.BadRequestError

class ProxmoxGuestIdentityTest {
    private fun withGuest(
        body: String,
        status: Int = 200,
        check: (ProxmoxClient, ProxmoxCluster) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val paths = mutableListOf<String>()
        server.createContext("/") { request ->
            paths += request.requestMethod + " " + request.requestURI.path
            val bytes = body.toByteArray()
            request.sendResponseHeaders(status, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            check(
                ProxmoxClient(ObjectMapper()),
                ProxmoxCluster(
                    apiUrl = "http://127.0.0.1:${server.address.port}",
                    tokenId = "test",
                    tokenSecret = "test",
                ),
            )
            assertEquals(1, paths.size)
            assertEquals(true, paths.single().startsWith("GET "))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `reused numeric ID rejects old machine even with same hostname and IP`() {
        withGuest(
            """{"data":{"description":"microcloud-machine:502","hostname":"same","net0":"ip=10.0.0.2/24"}}"""
        ) { client, cluster ->
            assertThrows(IllegalStateException::class.java) {
                client.verifyGuestIdentity(cluster, "pve", 212, false, 501, "same", "10.0.0.2")
            }
        }
    }

    @Test
    fun `matching marker accepts VM independently of mutable display name`() {
        withGuest("""{"data":{"description":"microcloud-machine:502"}}""") { client, cluster ->
            client.verifyGuestIdentity(cluster, "pve", 212, true, 502, "same", "10.0.0.2")
        }
    }

    @Test
    fun `legacy guest requires matching hostname and exact address`() {
        withGuest(
            """{"data":{"hostname":"legacy","net0":"name=eth0,ip=10.0.0.20/24,gw=10.0.0.1"}}"""
        ) { client, cluster ->
            assertThrows(IllegalStateException::class.java) {
                client.verifyGuestIdentity(cluster, "pve", 212, false, 501, "legacy", "10.0.0.2")
            }
        }
    }

    @Test
    fun `legacy VM keeps lifecycle access when both original fields match`() {
        withGuest("""{"data":{"name":"legacy","ipconfig0":"ip=10.0.0.2/24,gw=10.0.0.1"}}""") {
            client,
            cluster ->
            client.verifyGuestIdentity(cluster, "pve", 212, true, 501, "legacy", "10.0.0.2")
        }
    }

    @Test
    fun `missing guest permission response remains an error`() {
        withGuest("""{"message":"Permission check failed","data":null}""", 403) { client, cluster ->
            assertThrows(BadRequestError::class.java) {
                client.verifyGuestIdentity(cluster, "pve", 212, false, 501, "legacy", "10.0.0.2")
            }
        }
    }
}
