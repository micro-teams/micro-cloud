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

    /**
     * The directory the template catalog is enumerated from. Each `*.tar.zst` image found under it
     * is a template — the template name is the image's parent directory, the kind is its
     * grandparent (`lxc` / `vm`). In the shipped bundle this is `templates/` mounted at
     * `/templates`.
     */
    var templatesDir: String = "/templates"

    /** Proxmox provisioning knobs (credentials come from each cluster, not here). */
    var provisioning: Provisioning = Provisioning()

    // These default to the shipped bundle's layout (gen-env.sh generates ./keys/operator, compose
    // mounts it at /keys), so provisioning works with zero manual configuration. Override only for
    // a
    // non-standard deployment (e.g. set init-command blank to skip SSH init entirely).
    class Provisioning {
        /**
         * Root SSH public key injected into new containers so the backend can SSH in to init them.
         * If blank, it is read from `${sshPrivateKeyPath}.pub`.
         */
        var rootSshPublicKey: String? = null
        /** The root SSH private key used to run init-machine.py over SSH. */
        var sshPrivateKeyPath: String? = "/keys/operator"
        /**
         * Remote command run as root over SSH to initialize a fresh container. Placeholders {user}
         * {sshPubkey} {ip} {gateway} are substituted. Blank skips SSH init (the container is still
         * created + started). The default runs the baked init-machine.py: creates the non-root
         * login user + authorizes its key, grants sudo + docker, then hardens (disables root SSH,
         * ufw).
         */
        var initCommand: String? =
            "python3 /root/init-machine.py --user '{user}' --ssh-pubkey '{sshPubkey}'"
        /**
         * Remote command for VM init. Unlike LXC (root SSH + baked script), a VM's login user is
         * created by cloud-init at clone (with the login user's key + the operator key + a static
         * IP), and the backend then SSHes in AS THE LOGIN USER with the operator key and pipes
         * `templates/vm/<template>/init-machine.py` to this command (`-` reads the script from
         * stdin) to install per-user software (Claude Code, …) and finish setup. `{user}`
         * {sshPubkey} are substituted. No `--ip`: cloud-init already set the address. Blank skips
         * VM init (the machine is still cloned + booted + reachable). The `sudo` uses the login
         * user's passwordless sudo that cloud-init grants.
         */
        var vmInitCommand: String? = "sudo python3 - --user '{user}' --ssh-pubkey '{sshPubkey}'"
        /** How long to wait for a Proxmox create/start task to finish. */
        var taskTimeoutSeconds: Long = 180
        /** How long to wait for a freshly-started machine to accept SSH (TCP :22) before init. */
        var sshReadyTimeoutSeconds: Long = 120

        // ---- VM template baking (see TemplateUploader) ----
        /**
         * The cloud-init login user created in the throwaway VM while baking a VM template. The
         * backend SSHes in as this user (with the operator key) to run the template's build.sh.
         */
        var vmBakeUser: String = "mcbake"
        /** CPU cores / RAM (MiB) for the throwaway bake VM (small; it only runs apt + a script). */
        var vmBakeCores: Int = 2
        var vmBakeMemoryMb: Int = 2048
        /**
         * Overall budget for the bake's long-running steps: the base-image download, and running
         * build.sh (apt install Docker) over SSH. Proxmox short tasks use [taskTimeoutSeconds].
         */
        var vmBakeTimeoutSeconds: Long = 1800
    }
}
