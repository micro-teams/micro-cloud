/*
 *  Description: MicroCloud's own configuration (prefix `microcloud`), separate from the borrowed
 *               org.rucca.cheese ApplicationConfig.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.stereotype.Component

@Component
@EnableAsync
@ConfigurationProperties(prefix = "microcloud")
class MicroCloudConfig {
    /** The platform operator's password. Injected at runtime; never committed. */
    lateinit var superadminPassword: String

    /** The synthetic user id the super-admin's tokens are minted under. */
    var superadminId: Long = 1

    /** The template catalog, declared at build/deploy time and seeded into the DB on startup. */
    var templates: List<TemplateEntry> = emptyList()

    class TemplateEntry {
        lateinit var name: String
        var description: String? = null
        var kind: String = "lxc"
        /** Where the image comes from when uploading: a local file path or an http(s) URL. */
        var source: String? = null
    }

    /** Proxmox provisioning knobs (credentials come from each cluster, not here). */
    var provisioning: Provisioning = Provisioning()

    class Provisioning {
        /** Fallback ostemplate volid when a template has no uploaded image on the placement. */
        var osTemplate: String? = null
        /** Root SSH public key injected into new containers (so the backend can init them). */
        var rootSshPublicKey: String? = null
        /** Path to the matching root SSH private key used to run init-machine.py over SSH. */
        var sshPrivateKeyPath: String? = null
        /**
         * Remote command run as root over SSH to initialize a fresh container. Placeholders {user}
         * {sshPubkey} {ip} {gateway} are substituted. If null, SSH init is skipped (the container
         * is still created + started).
         */
        var initCommand: String? = null
        /** How long to wait for a Proxmox create/start task to finish. */
        var taskTimeoutSeconds: Long = 180
    }
}
