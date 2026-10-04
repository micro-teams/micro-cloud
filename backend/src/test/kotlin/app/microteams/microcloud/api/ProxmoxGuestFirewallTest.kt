/*
 *  Description: A VM's network isolation. The policy is checked by evaluating it the way the
 *               Proxmox firewall does (first matching rule wins, then the default policy), and the
 *               client is run against an HTTP endpoint that stores firewall rules the way Proxmox
 *               does: each new rule goes to the top, disabled unless the request enables it.
 *
 *  Author(s):
 *      Zhifei Li    <andylizf@outlook.com>
 *
 */

package app.microteams.microcloud.api

import app.microteams.microcloud.machine.proxmox.GuestFirewallRule
import app.microteams.microcloud.machine.proxmox.ProxmoxClient
import app.microteams.microcloud.machine.proxmox.ProxmoxCluster
import app.microteams.microcloud.machine.proxmox.guestIsolationRules
import app.microteams.microcloud.machine.proxmox.reachableEndpoint
import app.microteams.microcloud.machine.proxmox.relayEndpoint
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProxmoxGuestFirewallTest {
    private val guests = listOf("10.20.0.1-10.20.0.254", "10.21.0.7")
    private val relay = "10.1.0.5" to 8090
    private val gateway = "10.1.0.9" to 443
    private val rules = guestIsolationRules(guests, listOf(relay, gateway))

    // ---- the policy ----

    private fun ipv4(address: String): Long =
        address.split('.').fold(0L) { acc, part -> acc * 256 + part.toLong() }

    private fun matches(spec: String?, address: String): Boolean {
        if (spec == null) return true
        if (':' in spec || ':' in address) return false // IPv6 specs never match IPv4 here
        val ip = ipv4(address)
        return when {
            '/' in spec -> {
                val (net, bits) = spec.split('/')
                val mask =
                    if (bits.toInt() == 0) 0L
                    else (0xFFFFFFFFL shl (32 - bits.toInt())) and 0xFFFFFFFFL
                ip and mask == ipv4(net) and mask
            }
            '-' in spec -> spec.split('-').let { (lo, hi) -> ip in ipv4(lo)..ipv4(hi) }
            else -> ip == ipv4(spec)
        }
    }

    /** What the Proxmox firewall does with a new connection: first match, else ACCEPT. */
    private fun verdict(type: String, peer: String, proto: String, port: Int): String =
        rules
            .firstOrNull {
                it.type == type &&
                    matches(if (type == "out") it.dest else it.source, peer) &&
                    (it.proto == null || it.proto == proto) &&
                    (it.dport == null || it.dport == "$port")
            }
            ?.action ?: "ACCEPT"

    @Test
    fun `a guest reaches DNS, the listed private endpoints and the internet`() {
        assertEquals("ACCEPT", verdict("out", "10.3.3.3", "udp", 53))
        assertEquals("ACCEPT", verdict("out", "10.1.0.9", "tcp", 443))
        assertEquals("DROP", verdict("out", "10.1.0.9", "tcp", 22))
        assertEquals("ACCEPT", verdict("out", "10.3.3.3", "tcp", 53))
        assertEquals("ACCEPT", verdict("out", "10.1.0.5", "tcp", 8090))
        assertEquals("ACCEPT", verdict("out", "140.82.112.3", "tcp", 443))
        // A fake-IP resolver hands out public names from 198.18.0.0/15.
        assertEquals("ACCEPT", verdict("out", "198.18.0.62", "tcp", 443))
    }

    @Test
    fun `a guest reaches nothing private beyond those`() {
        // The relay host's other ports: the control plane's own API sits there.
        assertEquals("DROP", verdict("out", "10.1.0.5", "tcp", 80))
        assertEquals("DROP", verdict("out", "10.1.0.5", "tcp", 22))
        assertEquals("DROP", verdict("out", "192.168.1.8", "tcp", 22))
        assertEquals("DROP", verdict("out", "172.20.0.1", "tcp", 5432))
        assertEquals("DROP", verdict("out", "169.254.169.254", "tcp", 80))
        assertEquals("DROP", verdict("out", "100.64.1.1", "tcp", 22))
        assertEquals("DROP", verdict("out", "10.20.0.9", "tcp", 22))
        assertEquals(
            listOf("fe80::/10", "fc00::/7"),
            rules.mapNotNull { it.dest?.takeIf { ':' in it } },
        )
    }

    @Test
    fun `other guests cannot connect in, the rest of the network can`() {
        assertEquals("DROP", verdict("in", "10.20.0.9", "tcp", 22))
        assertEquals("DROP", verdict("in", "10.21.0.7", "tcp", 22))
        assertEquals("ACCEPT", verdict("in", "10.1.0.5", "tcp", 22))
        assertEquals("ACCEPT", verdict("in", "192.168.1.5", "tcp", 22))
        assertEquals(listOf("fe80::/10"), rules.mapNotNull { it.source?.takeIf { ':' in it } })
    }

    @Test
    fun `with nothing listed no private port is open`() {
        val bare = guestIsolationRules(guests, emptyList())
        assertEquals(rules.filterNot { it.dest == "10.1.0.5" || it.dest == "10.1.0.9" }, bare)
    }

    @Test
    fun `a listed endpoint is host and port`() {
        assertEquals("10.1.0.9" to 443, reachableEndpoint("10.1.0.9:443"))
        assertEquals(
            InetAddress.getByName("localhost").hostAddress to 8443,
            reachableEndpoint("localhost:8443"),
        )
        assertThrows(IllegalArgumentException::class.java) { reachableEndpoint("10.1.0.9") }
    }

    @Test
    fun `the relay is the base URL's address and port`() {
        assertEquals("10.1.0.5" to 8090, relayEndpoint("http://10.1.0.5:8090/newapi"))
        assertEquals("10.1.0.5" to 80, relayEndpoint("http://10.1.0.5/newapi"))
        assertEquals("10.1.0.5" to 443, relayEndpoint("https://10.1.0.5/newapi"))
        assertEquals(
            InetAddress.getByName("localhost").hostAddress to 3000,
            relayEndpoint("http://localhost:3000/newapi"),
        )
        assertNull(relayEndpoint(""))
        assertNull(relayEndpoint(null))
    }

    // ---- the client against a Proxmox-shaped endpoint ----

    /** Holds one VM's config and firewall the way Proxmox does. */
    private class FakeVm(
        val honoursEnable: Boolean = true,
        val kind: String = "qemu",
        var net0: String = "virtio=BC:24:11:00:00:01,bridge=vmbr0",
    ) {
        val ipset = mutableListOf<String>()
        val rules = mutableListOf<Map<String, String>>()
        var options = mapOf<String, String>()
        val mapper = ObjectMapper()

        fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path.removePrefix("/api2/json/nodes/pve/$kind/300")
            val form =
                exchange.requestBody
                    .readAllBytes()
                    .toString(StandardCharsets.UTF_8)
                    .split('&')
                    .filter { it.isNotBlank() }
                    .associate {
                        val (k, v) = it.split('=', limit = 2)
                        k to URLDecoder.decode(v, StandardCharsets.UTF_8)
                    }
            val data: Any? =
                when ("${exchange.requestMethod} $path") {
                    "GET /config" -> mapOf("net0" to net0)
                    "PUT /config" -> null.also { net0 = form.getValue("net0") }
                    "POST /firewall/ipset" -> null
                    "POST /firewall/ipset/ipfilter-net0" ->
                        null.also { ipset += form.getValue("cidr") }
                    "POST /firewall/rules" -> {
                        val rule = form.toMutableMap()
                        if (!honoursEnable || "enable" !in rule) rule["enable"] = "0"
                        rules.add(0, rule)
                        null
                    }
                    "GET /firewall/rules" ->
                        rules
                            .mapIndexed { i, r -> r + ("pos" to "$i") }
                            .map { r ->
                                r.mapValues { (k, v) ->
                                    if (k == "enable" || k == "pos") v.toInt() else v
                                }
                            }
                    "PUT /firewall/options" -> null.also { options = form }
                    "GET /firewall/options" -> options.mapValues { (_, v) -> v.toIntOrNull() ?: v }
                    else -> error("unexpected ${exchange.requestMethod} $path")
                }
            val bytes = mapper.writeValueAsBytes(mapOf("data" to data))
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun withVm(vm: FakeVm, run: (ProxmoxClient, ProxmoxCluster) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                vm.handle(exchange)
            } catch (e: Exception) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            run(
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

    @Test
    fun `isolating a VM leaves its firewall holding exactly the policy`() {
        val vm = FakeVm()
        withVm(vm) { client, cluster -> client.isolateGuest(cluster, "pve", 300, true, "10.20.0.3", rules) }

        assertEquals("virtio=BC:24:11:00:00:01,bridge=vmbr0,firewall=1", vm.net0)
        assertEquals(listOf("10.20.0.3"), vm.ipset)
        assertEquals(
            rules,
            vm.rules.map {
                GuestFirewallRule(
                    it.getValue("type"),
                    it.getValue("action"),
                    it["source"],
                    it["dest"],
                    it["proto"],
                    it["dport"],
                )
            },
        )
        assertEquals(List(rules.size) { "1" }, vm.rules.map { it["enable"] })
        assertEquals(
            mapOf(
                "enable" to "1",
                "ipfilter" to "1",
                "policy_in" to "ACCEPT",
                "policy_out" to "ACCEPT",
            ),
            vm.options,
        )
    }

    @Test
    fun `isolating a container works the same way on its own path`() {
        val ct =
            FakeVm(
                kind = "lxc",
                net0 = "name=eth0,bridge=vmbr0,gw=10.20.0.1,hwaddr=BC:24:11:00:00:02,ip=10.20.0.4/24,type=veth",
            )
        withVm(ct) { client, cluster ->
            client.isolateGuest(cluster, "pve", 300, false, "10.20.0.4", rules)
        }

        assertEquals(
            "name=eth0,bridge=vmbr0,gw=10.20.0.1,hwaddr=BC:24:11:00:00:02,ip=10.20.0.4/24,type=veth,firewall=1",
            ct.net0,
        )
        assertEquals(listOf("10.20.0.4"), ct.ipset)
        assertEquals(rules.size, ct.rules.size)
        assertEquals("1", ct.options["enable"])
    }

    @Test
    fun `rules that land disabled fail the isolation`() {
        withVm(FakeVm(honoursEnable = false)) { client, cluster ->
            assertThrows(IllegalStateException::class.java) {
                client.isolateGuest(cluster, "pve", 300, true, "10.20.0.3", rules)
            }
        }
    }
}
