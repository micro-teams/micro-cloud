/*
 *  Description: The backend's operator SSH identity and the few SSH operations provisioning needs:
 *               read the operator public key (injected into new machines so the backend can log in),
 *               wait for a freshly-booted guest to accept TCP :22, and run a command or pipe a
 *               script over SSH. Shared by MachineProvisioner (LXC init over root SSH) and
 *               TemplateUploader (VM template bake over the bake user's SSH).
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.proxmox

import app.microteams.microcloud.common.config.MicroCloudConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class OperatorSsh(private val config: MicroCloudConfig) {
    private val log = LoggerFactory.getLogger(OperatorSsh::class.java)

    /**
     * The operator SSH public key injected into new machines: the configured value, or (default)
     * read from `${sshPrivateKeyPath}.pub`. Null when neither is available.
     */
    fun publicKey(): String? {
        config.provisioning.rootSshPublicKey
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
        val keyPath =
            config.provisioning.sshPrivateKeyPath?.takeIf { it.isNotBlank() } ?: return null
        val pub = File("$keyPath.pub")
        return if (pub.isFile) pub.readText().trim().ifBlank { null } else null
    }

    /** The operator private key path, or null if none is configured. */
    fun privateKeyPath(): String? =
        config.provisioning.sshPrivateKeyPath?.takeIf { it.isNotBlank() }

    /** Poll TCP :22 on the machine until it accepts a connection (the guest has booted enough). */
    fun waitForSsh(ip: String, timeoutSeconds: Long) {
        var waited = 0L
        while (waited < timeoutSeconds) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, 22), 3000) }
                return
            } catch (e: Exception) {
                Thread.sleep(3000)
                waited += 3
            }
        }
        throw IllegalStateException("$ip did not become SSH-reachable within ${timeoutSeconds}s")
    }

    /** Run [remote] on [ip] as [user] over SSH; throw with the output on a non-zero exit. */
    fun run(user: String, ip: String, remote: String, timeoutSeconds: Long) {
        val keyPath = privateKeyPath() ?: throw IllegalStateException("no operator SSH key")
        val (code, output) = exec(baseArgs(keyPath, user, ip) + remote, null, timeoutSeconds)
        if (code != 0) throw IllegalStateException("ssh '$remote' on $ip failed ($code): $output")
    }

    /**
     * Pipe [scriptFile] to [remoteCommand] (which must read the script from its stdin, e.g. `sudo
     * bash -s` or `sudo python3 - --user x`) on [ip] as [user]; throw with output on a non-zero
     * exit.
     */
    fun runScript(
        user: String,
        ip: String,
        scriptFile: File,
        remoteCommand: String,
        timeoutSeconds: Long,
    ) {
        val keyPath = privateKeyPath() ?: throw IllegalStateException("no operator SSH key")
        val (code, output) =
            exec(baseArgs(keyPath, user, ip) + remoteCommand, scriptFile, timeoutSeconds)
        if (code != 0) throw IllegalStateException("piped script on $ip failed ($code): $output")
        log.info("piped script on {} ({}) succeeded", ip, remoteCommand)
    }

    private fun baseArgs(keyPath: String, user: String, ip: String): List<String> =
        listOf(
            "ssh",
            "-i",
            keyPath,
            "-o",
            "StrictHostKeyChecking=no",
            "-o",
            "UserKnownHostsFile=/dev/null",
            "-o",
            "ConnectTimeout=20",
            "$user@$ip",
        )

    private fun exec(
        args: List<String>,
        stdinFile: File?,
        timeoutSeconds: Long,
    ): Pair<Int, String> {
        val builder = ProcessBuilder(args).redirectErrorStream(true)
        if (stdinFile != null) builder.redirectInput(stdinFile)
        val process = builder.start()
        if (stdinFile == null) process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("ssh to timed out after ${timeoutSeconds}s")
        }
        return process.exitValue() to output
    }
}
