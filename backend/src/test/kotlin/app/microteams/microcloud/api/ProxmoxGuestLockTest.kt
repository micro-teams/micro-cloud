/*
 * Description: A guest Proxmox refuses to touch because it is locked is reported as such, whether
 *              the refusal is a task's exit status or the answer to the request itself.
 * Author(s):
 *     Zhifei Li <andylizf@outlook.com>
 */
package app.microteams.microcloud.api

import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.proxmox.ProxmoxCluster
import app.microteams.microcloud.machine.proxmox.ProxmoxGuestLocked
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.rucca.cheese.common.error.BadRequestError

class ProxmoxGuestLockTest {
    /** A fake Proxmox answering every request with [status] and [body]. */
    private fun withProxmox(
        status: Int,
        body: String,
        check: (ProxmoxClient, ProxmoxCluster) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
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
        } finally {
            server.stop(0)
        }
    }

    private fun taskEnded(exit: String) =
        ObjectMapper()
            .writeValueAsString(mapOf("data" to mapOf("status" to "stopped", "exitstatus" to exit)))

    @Test
    fun `a start that ended on the fstrim lock is a locked guest`() {
        withProxmox(200, taskEnded("CT is locked (fstrim)")) { client, cluster ->
            val e =
                assertThrows<ProxmoxGuestLocked> {
                    client.waitForTask(cluster, "UPID:pve119:1:2:3:vzstart:170:test:", 4)
                }
            assertTrue(e.message!!.contains("CT is locked (fstrim)"))
        }
    }

    @Test
    fun `a VM task that timed out on its config lock is a locked guest`() {
        withProxmox(
            200,
            taskEnded("can't lock file '/var/lock/qemu-server/lock-147.conf' - got timeout"),
        ) { client, cluster ->
            assertThrows<ProxmoxGuestLocked> {
                client.waitForTask(cluster, "UPID:pve119:1:2:3:qmstart:147:test:", 4)
            }
        }
    }

    @Test
    fun `a destroy refused up front because of the lock is a locked guest`() {
        withProxmox(500, """{"data":null,"message":"CT is locked (fstrim)\n"}""") { client, cluster
            ->
            assertThrows<ProxmoxGuestLocked> { client.destroyLxc(cluster, "pve119", 170) }
        }
    }

    @Test
    fun `other task failures stay failures`() {
        withProxmox(200, taskEnded("startup for container '170' failed")) { client, cluster ->
            assertThrows<BadRequestError> {
                client.waitForTask(cluster, "UPID:pve119:1:2:3:vzstart:170:test:", 4)
            }
        }
    }

    @Test
    fun `other refused requests stay failures`() {
        withProxmox(403, """{"data":null,"message":"Permission check failed"}""") { client, cluster
            ->
            assertThrows<BadRequestError> { client.destroyLxc(cluster, "pve119", 170) }
        }
    }
}
