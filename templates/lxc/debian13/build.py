#!/usr/bin/env python3
"""Build the MicroCloud "Debian 13 + Claude Code" LXC template.

An LXC template is a compressed rootfs tarball. Rather than assemble one from scratch, this routine
DOWNLOADS the standard Debian 13 LXC template Proxmox ships (~124 MB `.tar.zst` from
download.proxmox.com), customizes it in a chroot (installs base packages + Docker, bakes in
init-machine.py, enables root SSH for first-boot init), and repackages it into our template tarball.
Claude Code is
NOT baked in — init-machine.py installs it per-user at first boot to keep the template small.

It is the COMPILE step for the template: the output is what ships in the bundle; this script does
not. stdlib-only (no pip packages); it shells out to `tar`, `chroot`, `mount`, `apt` — not Python
packages. Run as root (chroot + mount). amd64 host building an amd64 template (no emulation).

Example:
    sudo ./build.py --output ./dist/debian13-microcloud.tar.zst
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
FILES_DIR = os.path.join(HERE, "files")
INIT_SCRIPT = os.path.join(HERE, "init-machine.py")

# The standard Debian 13 LXC template Proxmox distributes (a plain rootfs .tar.zst).
DEFAULT_BASE_URL = (
    "http://download.proxmox.com/images/system/debian-13-standard_13.6-1_amd64.tar.zst"
)


def log(msg: str) -> None:
    print(f"[build] {msg}", flush=True)


def run(cmd: list[str], **kw) -> None:
    log("+ " + " ".join(cmd))
    subprocess.run(cmd, check=True, **kw)


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Build the Debian 13 MicroCloud LXC template tarball.")
    p.add_argument("--output", required=True, help="path to write the resulting template tarball")
    p.add_argument("--base-url", default=DEFAULT_BASE_URL,
                   help="standard Debian 13 LXC template: an http(s) URL or a local .tar.zst path")
    p.add_argument("--workdir", default=None,
                   help="scratch directory (default: a fresh temp dir, removed afterwards)")
    return p.parse_args(argv)


def read_packages() -> list[str]:
    """Extra apt packages to install, one per line in files/packages.txt ('#' comments allowed)."""
    pkgs: list[str] = []
    with open(os.path.join(FILES_DIR, "packages.txt")) as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                pkgs.append(line)
    return pkgs


def fetch_base(base: str, dest: str) -> str:
    if base.startswith(("http://", "https://")):
        log(f"downloading standard Debian 13 LXC template: {base}")
        urllib.request.urlretrieve(base, dest)
        return dest
    if not os.path.isfile(base):
        sys.exit(f"base template not found: {base}")
    return base


def extract_rootfs(tarball: str, rootfs: str) -> None:
    os.makedirs(rootfs, exist_ok=True)
    log(f"extracting base rootfs into {rootfs}")
    run(["tar", "--zstd", "--numeric-owner", "-xpf", tarball, "-C", rootfs])


def customize(rootfs: str, packages: list[str]) -> None:
    """Install packages + Claude Code and bake in init-machine.py, inside a chroot."""
    shutil.copy("/etc/resolv.conf", os.path.join(rootfs, "etc/resolv.conf"))
    mounts = ["proc", "sys", "dev"]
    for m in mounts:
        run(["mount", "--bind", f"/{m}", os.path.join(rootfs, m)])
    try:
        shutil.copy(INIT_SCRIPT, os.path.join(rootfs, "root/init-machine.py"))
        os.chmod(os.path.join(rootfs, "root/init-machine.py"), 0o755)
        script = f"""
set -eux
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends {' '.join(packages)}

# Docker, via Docker's official apt repository (the standard Debian install flow). Machines are
# unprivileged LXCs with nesting enabled, so the Docker daemon runs inside them.
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
CODENAME="$(. /etc/os-release && echo "$VERSION_CODENAME")"
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $CODENAME stable" > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable docker || true

# Rust toolchain (system-wide) + jujutsu (jj). jujutsu is not in Debian 13 stable, so install the
# Rust toolchain (rustup, system-wide under /opt/rust — rustc/cargo/rustup on every user's PATH, each
# user's cargo still writes to their own ~/.cargo) and drop in jj from a PREBUILT release binary
# (compiling jj from source is far too slow for a bake). build-essential/pkg-config stay so a user's
# own `cargo build` has a linker. KEEP THIS BLOCK IN SYNC WITH templates/vm/debian13/build.sh.
apt-get install -y --no-install-recommends build-essential pkg-config
export RUSTUP_HOME=/opt/rust CARGO_HOME=/opt/rust
curl --proto '=https' --tlsv1.2 -fsSL https://sh.rustup.rs | sh -s -- -y --no-modify-path --profile minimal
ln -sf /opt/rust/bin/cargo /opt/rust/bin/rustc /opt/rust/bin/rustup /usr/local/bin/
echo 'export RUSTUP_HOME=/opt/rust' > /etc/profile.d/rust.sh
chmod -R a+rX /opt/rust
# jujutsu: resolve the latest release tag via the redirect, fetch the prebuilt musl binary, install jj.
JJ_VER=$(curl -fsSLI -o /dev/null -w '%{{url_effective}}' https://github.com/jj-vcs/jj/releases/latest | sed 's#.*/tag/##')
JJ_TMP=$(mktemp -d)
curl -fsSL "https://github.com/jj-vcs/jj/releases/download/${{JJ_VER}}/jj-${{JJ_VER}}-x86_64-unknown-linux-musl.tar.gz" | tar -xz -C "$JJ_TMP"
install -m755 "$(find "$JJ_TMP" -name jj -type f | head -1)" /usr/local/bin/jj
rm -rf "$JJ_TMP"

# Claude Code is NOT baked into the template — it is installed per-user at first boot by
# init-machine.py (via claude.ai/install.sh). That keeps the template small, always installs the
# latest, and runs the installer on a real running system rather than in this chroot (where its
# self-install step hangs).
# Root may SSH into a freshly created machine only until init-machine.py hardens it away.
sed -i 's/^#*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
systemctl enable ssh || true
apt-get clean
rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
"""
        run(["chroot", rootfs, "/bin/bash", "-c", script])
    finally:
        for m in reversed(mounts):
            subprocess.run(["umount", "-lf", os.path.join(rootfs, m)], check=False)


def repackage(rootfs: str, output: str) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(output)) or ".", exist_ok=True)
    log(f"packaging template -> {output}")
    # Keep the mount-point dirs but never their (runtime-only) contents — they are populated by the
    # kernel at boot, and archiving the live /sys/proc/dev bloats the tarball and trips tar's
    # "file shrank" fatal.
    excludes = [f"--exclude=./{d}/*" for d in ("proc", "sys", "dev", "run")]
    run(["tar", "--zstd", "--numeric-owner", *excludes, "-cpf", output, "-C", rootfs, "."])
    size_mb = os.path.getsize(output) / (1024 * 1024)
    log(f"done: {output} ({size_mb:.0f} MB)")


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if os.geteuid() != 0:
        sys.exit("must run as root (chroot + mount)")

    owns_workdir = args.workdir is None
    workdir = args.workdir or tempfile.mkdtemp(prefix="microcloud-lxc-")
    rootfs = os.path.join(workdir, "rootfs")
    try:
        base = fetch_base(args.base_url, os.path.join(workdir, "base.tar.zst"))
        extract_rootfs(base, rootfs)
        customize(rootfs, read_packages())
        repackage(rootfs, args.output)
    finally:
        if owns_workdir:
            shutil.rmtree(workdir, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
