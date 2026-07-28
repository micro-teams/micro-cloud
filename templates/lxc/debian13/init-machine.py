#!/usr/bin/env python3
"""Initialize a freshly provisioned MicroCloud machine (Debian 13 LXC).

Baked into the template at /root/init-machine.py by build.py. The MicroCloud service runs it once on
a newly created container (preferably via `pct exec`, so root SSH need never be exposed) to create
the non-root user, inject its SSH key, optionally set a static IP, write the newapi relay config for
Claude Code, and harden the box.

It is a ROUTINE the service calls: every input is a command-line argument (no interactive prompts),
and stdlib-only (no pip packages) so it stays portable and easy to extend. Idempotent — safe to
re-run. Requires python3 in the container, which the template guarantees.

Run as root.

Example:
    ./init-machine.py --user alice --ssh-pubkey "ssh-ed25519 AAAA... alice" \
        --ip 192.168.16.42/20 --gateway 192.168.16.1 \
        --anthropic-base-url https://relay.internal/ --anthropic-token sk-...
"""

import argparse
import grp
import json
import os
import pwd
import subprocess
import sys


def log(msg: str) -> None:
    print(f"[init-machine] {msg}", flush=True)


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, **kw)


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Initialize a provisioned MicroCloud Debian 13 machine.")
    p.add_argument("--user", required=True, help="non-root user to create")
    p.add_argument("--ssh-pubkey", help="SSH public key to authorize for the user (preferred)")
    p.add_argument("--password-stdin", action="store_true",
                   help="read the user's password from stdin (never a CLI arg)")
    p.add_argument("--ip", help="static IPv4 for the machine, CIDR form e.g. 192.168.16.42/20")
    p.add_argument("--gateway", help="default gateway (required if --ip is given)")
    p.add_argument("--interface", default="eth0", help="network interface to configure (default eth0)")
    p.add_argument("--anthropic-base-url", help="newapi relay base URL for Claude Code")
    p.add_argument("--anthropic-token", help="newapi relay token (sk-...) for Claude Code")
    p.add_argument("--no-harden", action="store_true",
                   help="skip disabling root SSH and enabling the firewall")
    return p.parse_args(argv)


def user_exists(name: str) -> bool:
    try:
        pwd.getpwnam(name)
        return True
    except KeyError:
        return False


def create_user(name: str) -> None:
    if user_exists(name):
        log(f"user '{name}' already exists, skipping creation")
    else:
        run(["useradd", "-m", "-s", "/bin/bash", name])


def set_password_from_stdin(name: str) -> None:
    pw = sys.stdin.readline().rstrip("\n")
    run(["chpasswd"], input=f"{name}:{pw}\n", text=True)


def authorize_key(name: str, pubkey: str) -> None:
    home = pwd.getpwnam(name).pw_dir
    ssh_dir = os.path.join(home, ".ssh")
    os.makedirs(ssh_dir, mode=0o700, exist_ok=True)
    keys = os.path.join(ssh_dir, "authorized_keys")
    with open(keys, "w") as f:
        f.write(pubkey.rstrip("\n") + "\n")
    os.chmod(keys, 0o600)
    _chown_tree(ssh_dir, name)


def _chown_tree(path: str, name: str) -> None:
    uid = pwd.getpwnam(name).pw_uid
    gid = pwd.getpwnam(name).pw_gid
    os.chown(path, uid, gid)
    for root, dirs, filenames in os.walk(path):
        for d in dirs:
            os.chown(os.path.join(root, d), uid, gid)
        for fn in filenames:
            os.chown(os.path.join(root, fn), uid, gid)


def grant_sudo_and_docker(name: str) -> None:
    os.makedirs("/etc/sudoers.d", mode=0o755, exist_ok=True)
    sudoers = f"/etc/sudoers.d/{name}"
    with open(sudoers, "w") as f:
        f.write(f"{name} ALL=(ALL) NOPASSWD:ALL\n")
    os.chmod(sudoers, 0o440)
    if _group_exists("docker"):
        run(["usermod", "-aG", "docker", name])


def _group_exists(name: str) -> bool:
    try:
        grp.getgrnam(name)
        return True
    except KeyError:
        return False


def configure_network(interface: str, cidr: str, gateway: str) -> None:
    """Write a static-IP config (systemd-networkd). Called only when --ip is given."""
    if not gateway:
        sys.exit("--gateway is required when --ip is given")
    conf = (
        f"[Match]\nName={interface}\n\n"
        f"[Network]\nAddress={cidr}\nGateway={gateway}\n"
    )
    os.makedirs("/etc/systemd/network", exist_ok=True)
    path = f"/etc/systemd/network/10-{interface}.network"
    with open(path, "w") as f:
        f.write(conf)
    subprocess.run(["systemctl", "enable", "--now", "systemd-networkd"], check=False)
    log(f"wrote static network config {path} ({cidr} via {gateway})")


def write_ai_config(name: str, base_url: str, token: str) -> None:
    """Point Claude Code at the newapi relay via ~/.claude/settings.json's `env` block.

    settings.json's `env` overrides shell/system env AND — unlike a sourced ~/.profile snippet —
    reaches background/supervisor Claude sessions too (they don't inherit the launching shell). It is
    also the file ccproxy edits to switch this machine to a subscription login: ccproxy MERGES its
    proxy keys in, and on switch removes exactly these ANTHROPIC_* keys. So MERGE (read-modify-write),
    never overwrite — preserve any keys ccproxy or the user already placed there.
    """
    entry = pwd.getpwnam(name)
    home, uid, gid = entry.pw_dir, entry.pw_uid, entry.pw_gid

    claude_dir = os.path.join(home, ".claude")
    os.makedirs(claude_dir, mode=0o700, exist_ok=True)
    settings = os.path.join(claude_dir, "settings.json")
    try:
        has = os.path.exists(settings) and os.path.getsize(settings) > 0
        cfg = json.load(open(settings)) if has else {}
    except (ValueError, OSError):
        cfg = {}
    if not isinstance(cfg, dict):
        cfg = {}
    env = cfg.get("env")
    if not isinstance(env, dict):
        env = {}
    env["ANTHROPIC_BASE_URL"] = base_url
    env["ANTHROPIC_AUTH_TOKEN"] = token
    cfg["env"] = env
    tmp = settings + ".tmp"
    with open(tmp, "w") as f:
        json.dump(cfg, f, indent=2)
    os.replace(tmp, settings)
    os.chmod(settings, 0o600)

    # So interactive `claude` skips its first-run wizard and uses the configured relay directly.
    claude_json = os.path.join(home, ".claude.json")
    with open(claude_json, "w") as f:
        f.write('{"hasCompletedOnboarding":true}\n')
    _chown_tree(claude_dir, name)
    os.chown(claude_json, uid, gid)


def install_claude(name: str) -> None:
    """Install Claude Code for the user via the official installer.

    Not baked into the template (keeps it small); installed here at first boot, on a real running
    system (the installer's self-install hangs in a chroot but works here). It drops a native binary
    into the user's ~/.local/bin, which the stock Debian ~/.profile puts on PATH. Run as the user.
    """
    log(f"installing Claude Code for {name} (downloads a ~260 MB binary)")
    run(["sudo", "-u", name, "-H", "bash", "-lc",
         "curl -fsSL https://claude.ai/install.sh | bash"])


def harden() -> None:
    sshd = "/etc/ssh/sshd_config"
    if os.path.isfile(sshd):
        run(["sed", "-i", "s/^#*PermitRootLogin.*/PermitRootLogin no/", sshd])
        if subprocess.run(["systemctl", "restart", "ssh"], check=False).returncode != 0:
            subprocess.run(["systemctl", "restart", "sshd"], check=False)
    if _has("ufw"):
        run(["ufw", "default", "deny", "incoming"])
        run(["ufw", "default", "allow", "outgoing"])
        run(["ufw", "allow", "ssh"])
        subprocess.run(["ufw", "--force", "enable"], check=False)


def _has(prog: str) -> bool:
    return subprocess.run(["sh", "-c", f"command -v {prog}"],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if os.geteuid() != 0:
        sys.exit("must run as root")

    create_user(args.user)
    if args.password_stdin:
        set_password_from_stdin(args.user)
    if args.ssh_pubkey:
        authorize_key(args.user, args.ssh_pubkey)
    grant_sudo_and_docker(args.user)
    if args.ip:
        configure_network(args.interface, args.ip, args.gateway)
    if args.anthropic_base_url and args.anthropic_token:
        write_ai_config(args.user, args.anthropic_base_url, args.anthropic_token)
        install_claude(args.user)
    if not args.no_harden:
        harden()

    log(f"initialized user '{args.user}' on {os.uname().nodename}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
