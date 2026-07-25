# templates/lxc/debian13

The **v1 machine template**: a Debian 13 LXC with Claude Code installed. This is the only machine
form MicroCloud provisions for now.

These files are **routines the MicroCloud service calls** — everything is passed as command-line
arguments (no interactive prompts, no hardcoded paths), and they are written in **stdlib-only
Python** (no pip packages) so they stay portable and easy to extend.

## Layout

| path | what |
|---|---|
| `build.py` | Builds the LXC template — a compressed rootfs tarball (~100-200 MB). Takes an official Debian 13 rootfs, customizes it in a chroot (installs Claude Code + `files/packages.txt`, bakes in `init-machine.py`, enables root SSH for first-boot init), repackages it. Args: `--base`, `--output`, `--arch`, `--compress`, … Run as root. |
| `init-machine.py` | Initializes a freshly provisioned machine (baked into the template at `/root/init-machine.py`): creates the non-root user, injects its SSH key, optionally sets a static IP, writes the newapi relay config for Claude Code, hardens the box. Args: `--user`, `--ssh-pubkey`/`--password-stdin`, `--ip`/`--gateway`, `--anthropic-base-url`/`--anthropic-token`, `--no-harden`. Idempotent. Run as root. |
| `files/` | Supporting files these routines use. `packages.txt` — the apt packages baked into the template. |

## Lifecycle

1. **Once — build the template** (on a build host, as root):
   ```sh
   sudo ./build.py --base <debian-13-rootfs.tar.zst> --output ./dist/debian13-microcloud-amd64.tar.zst
   ```
   Upload the resulting tarball to Proxmox as an LXC template.

2. **Per machine — MicroCloud does this:** `pct create` a container from the template → assign a
   private IP from the configured IpRange(s) → `pct start` →
   ```sh
   pct exec <vmid> -- /root/init-machine.py --user <u> --ssh-pubkey <key> \
       --anthropic-base-url <newapi> --anthropic-token <sk-...>
   ```
   → deliver the private IP + user.

Root SSH is enabled in the template only so a freshly created machine can be reached before init;
`init-machine.py` disables it (preferring `pct exec` for init means root SSH need not be exposed at
all). Machines are reachable on the private network only — **no public SSH port**.
