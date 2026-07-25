# images/lxc/debian13

The **v1 machine template**: a Debian 13 LXC with Claude Code installed. This is the only machine
form MicroCloud provisions for now.

| file | what |
|---|---|
| `build-template.sh` | Run once on a Proxmox node: builds our template from the official Debian 13 LXC (installs Claude Code / node / git / docker / ufw / openssh), drops in `init-machine.sh`, converts to a PVE template. |
| `init-machine.sh` | Runs on each freshly cloned machine (via `pct exec`): creates the non-root user, injects its SSH key, writes the newapi relay config for Claude Code, and hardens the box (disables root SSH, minimal firewall). Non-interactive, argument-driven. |
| `template.conf` | Reference `pct` parameters (cores/memory/rootfs/features/network). |

## Lifecycle

1. **Once:** `build-template.sh` → a Proxmox template.
2. **Per machine (MicroCloud does this):** `pct clone` → assign a private IP from the configured
   IpRange(s) → `pct start` → `pct exec … init-machine.sh --user <u> --ssh-pubkey <key>
   --anthropic-base-url <newapi> --anthropic-token <sk-…>` → deliver the private IP + user.

Root SSH is enabled in the template only so a freshly cloned machine can be reached before init;
`init-machine.sh` disables it. Preferring `pct exec` for init means root SSH need not be exposed at
all. Machines are reachable on the private network only — **no public SSH port**.
