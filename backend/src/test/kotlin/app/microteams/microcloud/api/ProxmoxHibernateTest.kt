/*
 * Description: Verify hibernation requests release VM memory and use checkpoint restoration.
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
import org.junit.jupiter.api.Test

class ProxmoxHibernateTest {
    @Test
    fun `hibernation saves to disk and restoration never cold starts a guest`() {
        val requests = mutableListOf<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            requests += request.requestURI.path to request.requestBody.bufferedReader().readText()
            val bytes = """{"data":"UPID:test:1"}""".toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = ProxmoxClient(ObjectMapper())
            val cluster =
                ProxmoxCluster(
                    apiUrl = "http://127.0.0.1:${server.address.port}",
                    tokenId = "test",
                    tokenSecret = "test",
                )
            client.suspendVm(cluster, "node", 101)
            client.resumeVm(cluster, "node", 101)
            assertEquals(
                listOf(
                    "/api2/json/nodes/node/qemu/101/status/suspend" to "todisk=1",
                    "/api2/json/nodes/node/qemu/101/status/resume" to "",
                ),
                requests,
            )
        } finally {
            server.stop(0)
        }
    }
}
