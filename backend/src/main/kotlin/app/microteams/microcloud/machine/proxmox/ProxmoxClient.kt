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

    /** Convert a stopped VM into a template. Returns the task UPID. */
    fun templateVm(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/qemu/$vmid/template", emptyMap()).asText()

    fun startVm(cluster: ProxmoxCluster, node: String, vmid: Int): String =
        send(cluster, "POST", "/nodes/$node/qemu/$vmid/status/start", emptyMap()).asText()

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
        val node =
            upid.split(":").getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: throw BadRequestError("malformed Proxmox UPID: $upid")
        val deadline = timeoutSeconds
        var waited = 0L
        while (waited < deadline) {
            val status = send(cluster, "GET", "/nodes/$node/tasks/$upid/status", null)
            if (status.path("status").asText() == "stopped") {
                val exit = status.path("exitstatus").asText("")
                if (exit != "OK") throw BadRequestError("Proxmox task $upid failed: $exit")
                return
            }
            Thread.sleep(2000)
            waited += 2
        }
        throw BadRequestError("Proxmox task $upid did not finish within ${timeoutSeconds}s")
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
            throw BadRequestError(
                "Proxmox returned ${response.statusCode()} for $path: ${response.body()}"
            )
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
