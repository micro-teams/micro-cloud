/*
 *  Description: A thin Proxmox VE API client. Talks to a cluster's REST API (`/api2/json/...`) with a
 *               PVEAPIToken header, and exposes just what the platform needs today: a live inventory
 *               read (nodes / pools / storages / bridges) used to help configure placements. The
 *               client is stateless; a caller passes the target cluster's credentials per call.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.proxmox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.rucca.cheese.common.error.BadRequestError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** An inventory snapshot read live from a Proxmox cluster. */
data class ProxmoxInventory(
    val nodes: List<String>,
    val pools: List<String>,
    val storages: List<StorageEntry>,
    val bridges: List<BridgeEntry>,
) {
    data class StorageEntry(val node: String, val storage: String, val content: String)

    data class BridgeEntry(val node: String, val bridge: String, val cidr: String?)
}

/**
 * The task was still running when the wait gave up. Unlike a task that stopped with an error, its
 * work may still land, so a caller that created a guest must assume the guest will exist.
 */
class ProxmoxTaskTimeout(message: String) : RuntimeException(message)

/**
 * Proxmox refused to act on a guest because something else holds it: its config carries a lock (`CT
 * is locked (fstrim)` while a maintenance `pct fstrim` runs, `VM is locked (backup)`), or its
 * config file stayed flocked past the task's own wait (`can't lock file ... - got timeout`). The
 * guest was not touched, so the same request can be sent again once the holder lets go. Raised
 * whether the refusal came back from the request itself or as the exit status of its task.
 */
class ProxmoxGuestLocked(message: String) : RuntimeException(message)

private val GUEST_LOCKED = Regex("""\b(CT|VM) is locked \(|can't lock file '[^']*' - got timeout""")

/** See [ProxmoxClient.guestOwnership]. */
enum class GuestOwnership {
    OURS,
    ABSENT,
    FOREIGN,
    UNKNOWN,
}

@Component
class ProxmoxClient(private val objectMapper: ObjectMapper) {

    /** Read nodes, pools, and each node's storages and network bridges from the cluster. */
    fun readInventory(cluster: ProxmoxCluster): ProxmoxInventory {
        val nodes = get(cluster, "/nodes").map { it.path("node").asText() }.sorted()
        val pools = get(cluster, "/pools").map { it.path("poolid").asText() }.sorted()

        val storages = mutableListOf<ProxmoxInventory.StorageEntry>()
        val bridges = mutableListOf<ProxmoxInventory.BridgeEntry>()
        for (node in nodes) {
            get(cluster, "/nodes/$node/storage").forEach {
                storages.add(
                    ProxmoxInventory.StorageEntry(
                        node = node,
                        storage = it.path("storage").asText(),
                        content = it.path("content").asText(""),
                    )
                )
            }
            // type=bridge selects Linux bridges; a bridge may have no address assigned.
            get(cluster, "/nodes/$node/network?type=bridge").forEach {
                val cidr = it.path("cidr").asText(null)?.ifBlank { null }
                bridges.add(
                    ProxmoxInventory.BridgeEntry(
                        node = node,
                        bridge = it.path("iface").asText(),
                        cidr = cidr,
                    )
                )
            }
        }
        return ProxmoxInventory(
            nodes = nodes,
            pools = pools,
            storages = storages,
            bridges = bridges,
        )
    }

    // ---- Template images (used by template upload) ----

    /** Storages on a node that accept `vztmpl` content (where LXC templates live). */
    fun vztmplStorages(cluster: ProxmoxCluster, node: String): List<String> =
        get(cluster, "/nodes/$node/storage?content=vztmpl").map { it.path("storage").asText() }

    /**
     * Ask Proxmox to download a template image from a URL into a storage. Returns the task UPID.
     */
    fun downloadTemplateFromUrl(
        cluster: ProxmoxCluster,
        node: String,
        storage: String,
        filename: String,
        url: String,
    ): String =
        send(
                cluster,
                "POST",
                "/nodes/$node/storage/$storage/download-url",
                mapOf("content" to "vztmpl", "filename" to filename, "url" to url),
            )
            .asText()

    /**
     * Upload a local template file into a storage via multipart/form-data. Returns the task UPID
     * (or an empty string if Proxmox answered synchronously). Streams the file, so large images are
     * fine.
     */
    fun uploadTemplateFile(
        cluster: ProxmoxCluster,
        node: String,
        storage: String,
        filename: String,
        filePath: String,
    ): String {
        val file = java.nio.file.Path.of(filePath)
        val boundary = "----microcloud" + java.lang.Long.toHexString(file.hashCode().toLong())
        val head = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"content\"\r\n\r\nvztmpl\r\n")
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"filename\"; filename=\"$filename\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }
        val tail = "\r\n--$boundary--\r\n"
        val body =
            HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(head),
                HttpRequest.BodyPublishers.ofFile(file),
                HttpRequest.BodyPublishers.ofString(tail),
            )
        val base = cluster.apiUrl!!.trimEnd('/')
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("$base/api2/json/nodes/$node/storage/$storage/upload"))
                .timeout(Duration.ofMinutes(30))
                .header("Authorization", "PVEAPIToken=${cluster.tokenId}=${cluster.tokenSecret}")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(body)
                .build()
        val response =
            try {
                clientFor(cluster).send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                throw BadRequestError("Proxmox upload failed: ${e.message}")
            }
        if (response.statusCode() !in 200..299)
            throw BadRequestError(
                "Proxmox upload returned ${response.statusCode()}: ${response.body()}"
            )
        return objectMapper.readTree(response.body()).path("data").asText("")
    }

    // ---- VM images + lifecycle (used by VM template baking and VM provisioning) ----

    /** Storages on a node that accept `import` content (where a VM base image is downloaded to). */
    fun importStorages(cluster: ProxmoxCluster, node: String): List<String> =
        get(cluster, "/nodes/$node/storage?content=import").map { it.path("storage").asText() }

    /**
     * Ask Proxmox to download a VM base image (e.g. a cloud qcow2) from a URL into an
     * import-capable storage. Returns the task UPID.
     */
    fun downloadImportImage(
        cluster: ProxmoxCluster,
        node: String,
        storage: String,
        filename: String,
        url: String,
    ): String =
        send(
                cluster,
                "POST",
                "/nodes/$node/storage/$storage/download-url",
                mapOf("content" to "import", "filename" to filename, "url" to url),
            )
            .asText()

    /** Create a QEMU VM on a node from form params (vmid, scsi0, ide2, net0, ciuser, …). UPID. */
    fun createVm(cluster: ProxmoxCluster, node: String, params: Map<String, String>): String =
        send(cluster, "POST", "/nodes/$node/qemu", params).asText()

    /** Full-clone a VM/template into a new vmid on the same node. Returns the clone task UPID. */
    fun cloneVm(
        cluster: ProxmoxCluster,
        node: String,
        sourceVmid: Int,
        params: Map<String, String>,
    ): String = send(cluster, "POST", "/nodes/$node/qemu/$sourceVmid/clone", params).asText()

    /** Update a VM's config (cloud-init ciuser / sshkeys / ipconfig0, cores, memory, …). Sync. */
    fun setVmConfig(cluster: ProxmoxCluster, node: String, vmid: Int, params: Map<String, String>) {
        send(cluster, "PUT", "/nodes/$node/qemu/$vmid/config", params)
    }

    /**
     * Grow a VM disk, e.g. disk=`scsi0`, size=`20G`. Returns the task UPID: the resize is a Proxmox
     * TASK that holds the VM's config lock until the volume has grown, and `qm start` gives up on
     * that lock after 10 s. On a busy thin pool the resize alone took 12 s (pve119, 2026-09-03, VM
     * 147: "can't lock file '/var/lock/qemu-server/lock-147.conf' - got timeout"), so a start
     * issued without waiting for this task fails exactly when the storage is slow. Await it with
     * [waitForTask] before touching the VM again.
     */
    fun resizeVmDisk(
        cluster: ProxmoxCluster,
        node: String,
        vmid: Int,
        disk: String,
        size: String,
    ): String =
        send(
                cluster,
                "PUT",
                "/nodes/$node/qemu/$vmid/resize",
                mapOf("disk" to disk, "size" to size),
            )
            .asText()

    /**
     * Put VM [vmid]'s `net0` behind the Proxmox firewall with exactly [rules] (see
     * [guestIsolationRules]), before the VM first starts. The ipfilter set holds only [ip], so the
     * guest can neither send from nor answer ARP for any other address on the shared segment. Reads
     * the rules back and throws unless they are exactly [rules], all enabled: Proxmox inserts each
     * new rule at the top and leaves it disabled unless told otherwise, so a rule list that reaches
     * it in the wrong shape still saves without an error.
     */
    fun isolateVm(
        cluster: ProxmoxCluster,
        node: String,
        vmid: Int,
        ip: String,
        rules: List<GuestFirewallRule>,
    ) {
        val base = "/nodes/$node/qemu/$vmid"
        val net0 = send(cluster, "GET", "$base/config", null).path("net0").asText("")
        check(net0.isNotBlank()) { "VM $vmid has no net0 to isolate" }
        val firewalled =
            (net0.split(',').filterNot { it.startsWith("firewall=") } + "firewall=1").joinToString(
                ","
            )
        send(cluster, "PUT", "$base/config", mapOf("net0" to firewalled))
        send(cluster, "POST", "$base/firewall/ipset", mapOf("name" to "ipfilter-net0"))
        send(cluster, "POST", "$base/firewall/ipset/ipfilter-net0", mapOf("cidr" to ip))
        for (rule in rules.asReversed()) {
            val form = buildMap {
                put("type", rule.type)
                put("action", rule.action)
                put("enable", "1")
                rule.source?.let { put("source", it) }
                rule.dest?.let { put("dest", it) }
                rule.proto?.let { put("proto", it) }
                rule.dport?.let { put("dport", it) }
            }
            send(cluster, "POST", "$base/firewall/rules", form)
        }
        send(
            cluster,
            "PUT",
            "$base/firewall/options",
            mapOf(
                "enable" to "1",
                "ipfilter" to "1",
                "policy_in" to "ACCEPT",
                "policy_out" to "ACCEPT",
            ),
        )
        val applied =
            get(cluster, "$base/firewall/rules").map {
                fun field(name: String) = it.path(name).asText("").ifBlank { null }
                GuestFirewallRule(
                    type = it.path("type").asText(),
                    action = it.path("action").asText(),
                    source = field("source"),
                    dest = field("dest"),
                    proto = field("proto"),
                    dport = field("dport"),
                ) to (it.path("enable").asInt(0) == 1)
            }
        check(applied == rules.map { it to true }) {
            "VM $vmid firewall rules read back as $applied, expected $rules"
        }
        val options = send(cluster, "GET", "$base/firewall/options", null)
        check(options.path("enable").asInt(0) == 1 && options.path("ipfilter").asInt(0) == 1) {
            "VM $vmid firewall options read back as $options"
        }
    }

    /** Convert a stopped VM into a template. Returns the task UPID. */
    fun templateVm(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/qemu/$vmid/template", emptyMap()).asText()

    fun startVm(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/qemu/$vmid/status/start", emptyMap()).asText()

    fun suspendVm(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/qemu/$vmid/status/suspend", mapOf("todisk" to "1"))
            .asText()

    fun resumeVm(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/qemu/$vmid/status/resume", emptyMap()).asText()

    /** HARD stop (pull the plug): no guest FS sync. Prefer [shutdownVm] except when destroying. */
    fun stopVm(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/qemu/$vmid/status/stop", emptyMap()).asText()

    /** Graceful ACPI shutdown: the guest flushes its filesystem and powers off cleanly. UPID. */
    fun shutdownVm(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/qemu/$vmid/status/shutdown", emptyMap()).asText()

    /** Destroy a VM (purge its config + disks, including unreferenced ones). */
    fun destroyVm(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(
                cluster,
                "DELETE",
                "/nodes/$node/qemu/$vmid?purge=1&destroy-unreferenced-disks=1",
                null,
            )
            .asText()

    /** Current run state of a VM: "running" / "stopped" / … */
    fun vmStatus(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "GET", "/nodes/$node/qemu/$vmid/status/current", null).path("status").asText()

    /**
     * Destroy a VM, stopping it first if it isn't already stopped. Unlike `pct destroy` for an LXC
     * (which purges even a running container), `qm destroy` REFUSES a running VM ("VM N is
     * running - destroy failed"), so a running VM must be `qm stop`ped first. Each step is awaited
     * via [waitForTask].
     */
    fun destroyVmGracefully(
        cluster: ProxmoxCluster,
        node: String,
        vmid: Int,
        timeoutSeconds: Long,
    ) {
        if (vmStatus(cluster, node, vmid) != "stopped")
            waitForTask(cluster, stopVm(cluster, node, vmid), timeoutSeconds)
        waitForTask(cluster, destroyVm(cluster, node, vmid), timeoutSeconds)
    }

    /**
     * Encode an SSH public key for the QEMU `sshkeys` cloud-init param, which Proxmox requires to
     * be URL-encoded ONCE by the caller (space as `%20`, not `+`). The transport form-encoding in
     * [send] then encodes it a second time, and Proxmox decodes exactly one layer — matching what
     * Proxmox stores. (LXC's `ssh-public-keys` takes the raw key and is NOT pre-encoded.)
     */
    fun sshkeysParam(pubkey: String): String =
        URLEncoder.encode(pubkey, StandardCharsets.UTF_8).replace("+", "%20")

    // ---- LXC lifecycle (used by machine provisioning) ----

    /** Numeric guest IDs are reusable; never treat one as proof of ownership. */
    fun verifyGuestIdentity(
        cluster: ProxmoxCluster,
        node: String,
        vmid: Int,
        vm: Boolean,
        machineId: Long,
        hostname: String,
        ip: String,
    ) {
        val kind = if (vm) "qemu" else "lxc"
        // An unreadable/missing guest is not evidence that deletion is safe. In particular,
        // a pool-scoped token returns 403 for absent guests; preserve the error and fail closed.
        val config = send(cluster, "GET", "/nodes/$node/$kind/$vmid/config", null)
        val marker = config.path("description").asText("").trim()
        val expected = "microcloud-machine:$machineId"
        if (marker.startsWith("microcloud-machine:")) {
            check(marker == expected) { "Guest $vmid belongs to another machine" }
            return
        }
        // Guests predating ownership markers retain their original hostname and static IP;
        // warm claim changes billing ownership only. Require both before any mutation.
        val name = config.path(if (vm) "name" else "hostname").asText("")
        val network = config.path(if (vm) "ipconfig0" else "net0").asText("")
        val address =
            network
                .split(',')
                .firstOrNull { it.startsWith("ip=") }
                ?.removePrefix("ip=")
                ?.substringBefore('/')
        check(name == hostname && address == ip) {
            "Guest $vmid identity does not match machine $machineId"
        }
    }

    /**
     * Whether guest [vmid] exists and passes [verifyGuestIdentity] for this machine. For cleaning
     * up after a failed create, where an unreadable or foreign guest is one not to touch.
     */
    fun ownsGuest(
        cluster: ProxmoxCluster,
        node: String,
        vmid: Int,
        vm: Boolean,
        machineId: Long,
        hostname: String,
        ip: String,
    ): Boolean =
        guestOwnership(cluster, node, vmid, vm, machineId, hostname, ip) == GuestOwnership.OURS

    /**
     * What guest [vmid] is to machine [machineId], for deciding whether deleting the machine may
     * destroy it, skip it, or must refuse.
     *
     * A pool-scoped token gets 403 for a guest it cannot see, whether that guest is absent or sits
     * outside the pool, so an unreadable config is settled by asking the cluster whether the id is
     * taken at all (`/cluster/nextid?vmid=`, open to every user): free means
     * [GuestOwnership.ABSENT]. A guest carrying another machine's marker is
     * [GuestOwnership.FOREIGN]: the id was reused, so this machine's guest is not there. Everything
     * else that is not provably ours, including a legacy guest whose hostname or address differ and
     * a taken id we cannot read, is [GuestOwnership.UNKNOWN], which a caller must treat as "do not
     * touch, do not forget".
     */
    fun guestOwnership(
        cluster: ProxmoxCluster,
        node: String,
        vmid: Int,
        vm: Boolean,
        machineId: Long,
        hostname: String,
        ip: String,
    ): GuestOwnership {
        val kind = if (vm) "qemu" else "lxc"
        val config =
            try {
                send(cluster, "GET", "/nodes/$node/$kind/$vmid/config", null)
            } catch (e: BadRequestError) {
                return if (vmidIsFree(cluster, vmid)) GuestOwnership.ABSENT
                else GuestOwnership.UNKNOWN
            }
        val marker = config.path("description").asText("").trim()
        if (marker.startsWith("microcloud-machine:")) {
            return if (marker == "microcloud-machine:$machineId") GuestOwnership.OURS
            else GuestOwnership.FOREIGN
        }
        return if (
            runCatching { verifyGuestIdentity(cluster, node, vmid, vm, machineId, hostname, ip) }
                .isSuccess
        )
            GuestOwnership.OURS
        else GuestOwnership.UNKNOWN
    }

    /**
     * Whether no guest anywhere in the cluster holds [vmid]. Proxmox answers 400 when it is taken.
     */
    private fun vmidIsFree(cluster: ProxmoxCluster, vmid: Int): Boolean =
        try {
            send(cluster, "GET", "/cluster/nextid?vmid=$vmid", null)
            true
        } catch (e: BadRequestError) {
            if (e.message?.contains("already exists") == true) false else throw e
        }

    /** Next free VM/CT id in the cluster. */
    fun nextVmid(cluster: ProxmoxCluster): Int =
        send(cluster, "GET", "/cluster/nextid", null).asText().toInt()

    /**
     * Create an LXC container on a node from form params (vmid, ostemplate, rootfs, net0, pool, …).
     * Returns the UPID of the create task; poll it with [waitForTask].
     */
    fun createLxc(cluster: ProxmoxCluster, node: String, params: Map<String, String>): String =
        send(cluster, "POST", "/nodes/$node/lxc", params).asText()

    fun startLxc(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/lxc/$vmid/status/start", emptyMap()).asText()

    /** HARD stop (pull the plug): no guest FS sync. Prefer [shutdownLxc]. */
    fun stopLxc(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/lxc/$vmid/status/stop", emptyMap()).asText()

    /** Graceful shutdown: the container flushes its filesystem and powers off cleanly. UPID. */
    fun shutdownLxc(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/lxc/$vmid/status/shutdown", emptyMap()).asText()

    /** Destroy an LXC (purge its config + disks; force even if running). */
    fun destroyLxc(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "DELETE", "/nodes/$node/lxc/$vmid?purge=1&force=1", null).asText()

    /** Current run state of an LXC: "running" / "stopped" / … */
    fun lxcStatus(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "GET", "/nodes/$node/lxc/$vmid/status/current", null).path("status").asText()

    /**
     * Poll a Proxmox task until it stops; throw if it exits non-OK or the timeout elapses. The task
     * runs on the node embedded in the UPID (`UPID:<node>:...`), which is NOT necessarily the node
     * the request was sent to — an upload to another node's storage runs on the API node and copies
     * across — so we always poll the UPID's own node.
     */
    fun waitForTask(cluster: ProxmoxCluster, upid: String, timeoutSeconds: Long = 120) {
        val parts = upid.split(":")
        val node =
            parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: throw BadRequestError("malformed Proxmox UPID: $upid")
        val startingLxc =
            if (parts.getOrNull(5) == "vzstart") parts.getOrNull(6)?.toIntOrNull() else null
        val deadline = timeoutSeconds
        var waited = 0L
        var monitorFailure: String? = null
        while (waited < deadline) {
            val status = send(cluster, "GET", "/nodes/$node/tasks/$upid/status", null)
            if (status.path("status").asText() == "stopped") {
                val exit = status.path("exitstatus").asText("")
                if (exit == "OK") return
                // Proxmox can finish vzstart before the new LXC monitor exposes its PID.
                // Reconcile that specific result without issuing another start request.
                if (
                    startingLxc == null ||
                        exit != "unable to get PID for CT $startingLxc (not running?)"
                ) {
                    if (GUEST_LOCKED.containsMatchIn(exit))
                        throw ProxmoxGuestLocked("Proxmox task $upid failed: $exit")
                    throw BadRequestError("Proxmox task $upid failed: $exit")
                }
                if (monitorFailure == null) {
                    LoggerFactory.getLogger(javaClass)
                        .warn("Proxmox task {} reported {}; checking container state", upid, exit)
                    monitorFailure = exit
                }
                if (lxcStatus(cluster, node, startingLxc) == "running") {
                    LoggerFactory.getLogger(javaClass)
                        .info("Proxmox task {} recovered: CT{} is running", upid, startingLxc)
                    return
                }
            }
            Thread.sleep(2000)
            waited += 2
        }
        if (monitorFailure != null) {
            throw BadRequestError(
                "Proxmox task $upid failed: $monitorFailure; container did not reach running within ${timeoutSeconds}s"
            )
        }
        throw ProxmoxTaskTimeout("Proxmox task $upid did not finish within ${timeoutSeconds}s")
    }

    /** GET `/api2/json{path}` and return the elements of the `data` array. */
    private fun get(cluster: ProxmoxCluster, path: String): List<JsonNode> {
        val data = send(cluster, "GET", path, null)
        return if (data.isArray) data.toList() else emptyList()
    }

    /**
     * Send a request to `/api2/json{path}` and return the `data` node. A non-null [form] is sent as
     * an `application/x-www-form-urlencoded` body (Proxmox's expected encoding for writes).
     */
    private fun send(
        cluster: ProxmoxCluster,
        method: String,
        path: String,
        form: Map<String, String>?,
    ): JsonNode {
        val base = cluster.apiUrl!!.trimEnd('/')
        val body =
            if (form == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(encodeForm(form))
        val builder =
            HttpRequest.newBuilder()
                .uri(URI.create("$base/api2/json$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "PVEAPIToken=${cluster.tokenId}=${cluster.tokenSecret}")
                .method(method, body)
        if (form != null) builder.header("Content-Type", "application/x-www-form-urlencoded")
        val response =
            try {
                clientFor(cluster).send(builder.build(), HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                throw BadRequestError("Proxmox request failed: ${e.message}")
            }
        if (response.statusCode() !in 200..299) {
            val message = "Proxmox returned ${response.statusCode()} for $path: ${response.body()}"
            // Some requests check the guest's lock before forking their task (pct destroy does).
            if (GUEST_LOCKED.containsMatchIn(response.body())) throw ProxmoxGuestLocked(message)
            throw BadRequestError(message)
        }
        return objectMapper.readTree(response.body()).path("data")
    }

    private fun encodeForm(form: Map<String, String>): String =
        form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }

    private fun clientFor(cluster: ProxmoxCluster): HttpClient {
        val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        if (!cluster.verifyTls) builder.sslContext(insecureSslContext())
        return builder.build()
    }

    /** For self-signed Proxmox certs when the operator opted out of TLS verification. */
    private fun insecureSslContext(): SSLContext {
        val trustAll =
            object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}

                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
        }
    }
}
