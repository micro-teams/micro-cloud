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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.rucca.cheese.common.error.BadRequestError

class ProxmoxHibernateTest {
    @Test
    fun `LXC monitor failure waits for the same container to run`() {
        startupResult(
            "vzstart",
            "unable to get PID for CT 101 (not running?)",
            listOf("stopped", "running"),
            true,
        )
    }

    @Test
    fun `LXC monitor failure remains a failure when the container stays stopped`() {
        startupResult(
            "vzstart",
            "unable to get PID for CT 101 (not running?)",
            listOf("stopped"),
            false,
        )
    }

    @Test
    fun `other startup failures are not hidden by a running container`() {
        startupResult("vzstart", "permission denied", listOf("running"), false)
    }

    @Test
    fun `VM tasks never use the LXC monitor recovery`() {
        startupResult(
            "qmstart",
            "unable to get PID for CT 101 (not running?)",
            listOf("running"),
            false,
        )
    }

    private fun startupResult(
        taskType: String,
        exit: String,
        states: List<String>,
        success: Boolean,
    ) {
        var reads = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            val data =
                if (request.requestURI.path.endsWith("/status/current")) {
                    mapOf("status" to states[minOf(reads++, states.lastIndex)])
                } else {
                    mapOf("status" to "stopped", "exitstatus" to exit)
                }
            val bytes = ObjectMapper().writeValueAsBytes(mapOf("data" to data))
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
            val wait = {
                client.waitForTask(
                    cluster,
                    "UPID:node:1:2:3:$taskType:101:test:",
                    if (success) 4 else 2,
                )
            }
            if (success) wait() else assertThrows(BadRequestError::class.java) { wait() }
            assertEquals(
                if (success) 2
                else if (taskType == "vzstart" && exit.startsWith("unable to get PID")) 1 else 0,
                reads,
            )
        } finally {
            server.stop(0)
        }
    }

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
