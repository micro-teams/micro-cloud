/*
 *  Description: The network isolation a guest gets from the Proxmox firewall: what it may reach
 *               (DNS, the internet, the newapi relay and
 *               the private endpoints the deployment lists) and what it may not (private addresses, which
 *               hold the control plane, the deployment's servers and the other guests). The rules
 *               sit on the host side of the guest's NIC, so root inside the guest cannot lift them.
 *
 *  Author(s):
 *      Zhifei Li    <andylizf@outlook.com>
 *
 */

package app.microteams.microcloud.machine.proxmox

import java.net.InetAddress
import java.net.URI

/**
 * One rule of a guest's Proxmox firewall, in the API's own terms: [type] is `in` / `out`, [action]
 * `ACCEPT` / `DROP`, addresses are an IP, a CIDR or a `first-last` range.
 */
data class GuestFirewallRule(
    val type: String,
    val action: String,
    val source: String? = null,
    val dest: String? = null,
    val proto: String? = null,
    val dport: String? = null,
)

/**
 * Destinations a guest may not reach. 198.18.0.0/15 is deliberately absent although it is not
 * public space: a fake-IP resolver answers every public name with an address from it, so dropping
 * it cuts the guest off the internet.
 */
private val PRIVATE_DESTINATIONS =
    listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "100.64.0.0/10",
        "fe80::/10",
        "fc00::/7",
    )

/**
 * The rules isolating a guest, in evaluation order. Outbound, each [blocked] host and port is
 * dropped first: a management port the deployment wants closed although its address is public,
 * which the private-range drops do not cover. Then the guest reaches DNS, each [reachable] private
 * host and port (the newapi relay, and whatever the deployment lists in
 * `microcloud.provisioning.guest-reachable`), and every public address; every other private one is
 * dropped. Inbound, the other guests ([guestRanges], plus IPv6 link-local) are dropped and everyone
 * else is let in, since the backend, the tenant and its own services reach the guest over SSH from
 * the private network; the guest's own firewall decides which of its ports answer.
 *
 * Port 53 is open to every destination because the resolver a guest is given is on a private
 * address the backend cannot read (it needs Sys.Audit on the node). ccproxy's engine is not let
 * through unless the deployment lists it: MicroCloud does not know its address.
 */
fun guestIsolationRules(
    guestRanges: List<String>,
    reachable: List<Pair<String, Int>>,
    blocked: List<Pair<String, Int>> = emptyList(),
): List<GuestFirewallRule> = buildList {
    blocked.forEach { (host, port) ->
        add(GuestFirewallRule("out", "DROP", dest = host, proto = "tcp", dport = "$port"))
    }
    add(GuestFirewallRule("out", "ACCEPT", proto = "udp", dport = "53"))
    add(GuestFirewallRule("out", "ACCEPT", proto = "tcp", dport = "53"))
    reachable.forEach { (host, port) ->
        add(GuestFirewallRule("out", "ACCEPT", dest = host, proto = "tcp", dport = "$port"))
    }
    PRIVATE_DESTINATIONS.forEach { add(GuestFirewallRule("out", "DROP", dest = it)) }
    guestRanges.forEach { add(GuestFirewallRule("in", "DROP", source = it)) }
    add(GuestFirewallRule("in", "DROP", source = "fe80::/10"))
}

/**
 * The address and port of a `host:port` entry of `microcloud.provisioning.guest-reachable` or
 * `guest-blocked`. A host name is resolved here, since a firewall rule takes addresses only.
 */
fun hostPortEndpoint(hostPort: String): Pair<String, Int> {
    val host = hostPort.substringBeforeLast(':')
    val port =
        hostPort.substringAfterLast(':', "").toIntOrNull()
            ?: throw IllegalArgumentException("$hostPort is not host:port")
    return InetAddress.getByName(host).hostAddress to port
}

/**
 * The address and port a guest dials for the newapi relay, from the machine-facing base URL, or
 * null when newapi is not wired. A host name is resolved here, since a firewall rule takes
 * addresses only.
 */
fun relayEndpoint(machineBaseUrl: String?): Pair<String, Int>? {
    val url = machineBaseUrl?.takeIf { it.isNotBlank() }?.let(URI::create) ?: return null
    val port =
        when {
            url.port != -1 -> url.port
            url.scheme == "https" -> 443
            else -> 80
        }
    return InetAddress.getByName(url.host).hostAddress to port
}
