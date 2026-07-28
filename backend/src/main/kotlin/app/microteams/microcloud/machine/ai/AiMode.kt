/*
 *  Description: How a machine's Claude Code gets its model access. NONE = no AI wired; NEWAPI = a
 *               per-machine newapi relay token (API-key style, fully automated, per-token billing);
 *               CCPROXY = a subscription-backed Claude Code via the ccproxy MITM (added later). The
 *               provisioner resolves an AiProvider from this and configures the machine accordingly.
 *               aiStatus tracks that configuration independently of the machine's own lifecycle.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.microcloud.machine.ai

enum class AiMode {
    NONE,
    NEWAPI,
    CCPROXY,
}

enum class AiStatus {
    /** No AI configured for this machine (mode NONE). */
    DISABLED,
    /** AI is being set up (token minted / config being applied). */
    PROVISIONING,
    /** AI is configured and usable on the machine. */
    READY,
    /** AI setup failed; the machine itself is unaffected. */
    ERROR,
}
