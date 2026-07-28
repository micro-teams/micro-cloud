/*
 *  Description: On-machine editor for the login user's ~/.claude/settings.json, over the operator SSH
 *               key (authorized on the login user by init-machine, so it works after hardening
 *               disables root). Used to restore the newapi env keys when a machine is switched BACK
 *               from ccproxy to newapi — ccproxy only removes those keys on the way to official; it
 *               never puts them back. The edit is a MERGE (read-modify-write) done by a tiny python
 *               program piped over SSH, preserving the proxy and every other key ccproxy/the user own.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

import app.microteams.microcloud.machine.instance.Machine
import app.microteams.microcloud.machine.proxmox.OperatorSsh
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import org.springframework.stereotype.Component

@Component
class MachineSettingsSsh(
    private val operatorSsh: OperatorSsh,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Re-add ANTHROPIC_BASE_URL / ANTHROPIC_AUTH_TOKEN to the login user's settings.json `env`
     * (merge, preserving all other keys). Base64's a python merge so nothing needs escaping over
     * SSH; machines ship python3.
     */
    fun restoreNewapiEnv(machine: Machine, baseUrl: String, token: String, timeoutSeconds: Long) {
        val baseJson = objectMapper.writeValueAsString(baseUrl)
        val tokenJson = objectMapper.writeValueAsString(token)
        val py =
            """
            import json, os
            p = os.path.expanduser("~/.claude/settings.json")
            os.makedirs(os.path.dirname(p), mode=0o700, exist_ok=True)
            try:
                cfg = json.load(open(p)) if os.path.exists(p) and os.path.getsize(p) else {}
            except Exception:
                cfg = {}
            if not isinstance(cfg, dict): cfg = {}
            env = cfg.get("env")
            if not isinstance(env, dict): env = {}
            env["ANTHROPIC_BASE_URL"] = $baseJson
            env["ANTHROPIC_AUTH_TOKEN"] = $tokenJson
            cfg["env"] = env
            t = p + ".tmp"
            json.dump(cfg, open(t, "w"), indent=2)
            os.replace(t, p)
            os.chmod(p, 0o600)
            """
                .trimIndent()
        val b64 = Base64.getEncoder().encodeToString(py.toByteArray())
        operatorSsh.run(
            machine.loginUser!!,
            machine.ip!!,
            "echo $b64 | base64 -d | python3",
            timeoutSeconds,
        )
    }
}
