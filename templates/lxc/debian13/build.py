#!/usr/bin/env python3
"""Build the MicroCloud "Debian 13 + Claude Code" LXC template.

An LXC template is a compressed rootfs tarball. Rather than assemble one from scratch, this routine
DOWNLOADS the standard Debian 13 LXC template Proxmox ships (~124 MB `.tar.zst` from
download.proxmox.com), customizes it in a chroot (installs base packages + Docker, bakes in
init-machine.py, enables root SSH for first-boot init), bakes the Claude Code binary under
/opt/claude, and repackages it into our template tarball. init-machine.py copies that binary into
the login user's own install layout at first boot, so a machine never downloads it: the download
(~260 MB from claude.ai) used to cost every machine 49 s of its boot and required a route to
claude.ai, which a private-subnet machine may not have.

It is the COMPILE step for the template: the output is what ships in the bundle; this script does
not. stdlib-only (no pip packages); it shells out to `tar`, `chroot`, `mount`, `apt` — not Python
packages. Run as root (chroot + mount). amd64 host building an amd64 template (no emulation).

Example:
    sudo ./build.py --output ./dist/debian13-microcloud.tar.zst
"""

import argparse
import hashlib
import json
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

# Claude Code's release layout, the same one claude.ai/install.sh reads:
#   <base>/latest                       a bare version string
#   <base>/<version>/manifest.json      platforms.<platform>.checksum (sha256 of the binary)
#   <base>/<version>/<platform>/claude  the native binary
# The template is amd64 glibc (see DEFAULT_BASE_URL), hence linux-x64.
CLAUDE_RELEASES = "https://downloads.claude.ai/claude-code-releases"
CLAUDE_PLATFORM = "linux-x64"
# Where the binary lives in the image. init-machine.py copies it from here into each login
# user's ~/.local/share/claude/versions/<version> and links ~/.local/bin/claude to it — the
# layout the official installer creates, so `claude update` and version pins keep working.
CLAUDE_BAKE_DIR = "opt/claude"


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
    p.add_argument("--claude-version", default="latest",
                   help="Claude Code version to bake (default: whatever <releases>/latest says "
                        "at build time; the exact version is recorded in /opt/claude/VERSION)")
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
    """Install packages and bake in init-machine.py, inside a chroot."""
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

# jujutsu (jj): a Git-compatible VCS not in Debian 13 stable. Install the official PREBUILT musl
# binary — it is self-contained, so NO Rust toolchain is needed on the machine (that would add
# hundreds of MB for no runtime benefit; anyone who wants Rust can install it themselves). The
# version is resolved from the releases/latest redirect (no GitHub API). KEEP THIS BLOCK IN SYNC
# WITH templates/vm/debian13/build.sh.
JJ_VER=$(curl -fsSLI -o /dev/null -w '%{{url_effective}}' https://github.com/jj-vcs/jj/releases/latest | sed 's#.*/tag/##')
JJ_TMP=$(mktemp -d)
curl -fsSL "https://github.com/jj-vcs/jj/releases/download/${{JJ_VER}}/jj-${{JJ_VER}}-x86_64-unknown-linux-musl.tar.gz" | tar -xz -C "$JJ_TMP"
install -m755 "$(find "$JJ_TMP" -name jj -type f | head -1)" /usr/local/bin/jj
rm -rf "$JJ_TMP"

# Claude Code is baked by bake_claude() below, from outside the chroot: only the binary is
# fetched (the official installer's self-install step hangs in a chroot, and nothing it does
# beyond placing the binary is needed).
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


def _fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as resp:
        return resp.read()


def bake_claude(rootfs: str, version: str) -> str:
    """Put the Claude Code binary into the image at /opt/claude/versions/<version>/claude.

    Verified against the sha256 the vendor publishes in the version's manifest, so a template
    never ships a truncated or tampered binary. `latest` is resolved here, at build time, and the
    version actually baked is written to /opt/claude/VERSION for init-machine.py and for anyone
    asking what a template contains. Returns the resolved version.
    """
    if version == "latest":
        version = _fetch(f"{CLAUDE_RELEASES}/latest").decode().strip()
    manifest = json.loads(_fetch(f"{CLAUDE_RELEASES}/{version}/manifest.json"))
    expected = manifest["platforms"][CLAUDE_PLATFORM]["checksum"]
    dest_dir = os.path.join(rootfs, CLAUDE_BAKE_DIR, "versions", version)
    os.makedirs(dest_dir, exist_ok=True)
    dest = os.path.join(dest_dir, "claude")
    log(f"baking Claude Code {version} ({CLAUDE_PLATFORM}) -> /{CLAUDE_BAKE_DIR}/versions/{version}/claude")
    digest = hashlib.sha256()
    with urllib.request.urlopen(f"{CLAUDE_RELEASES}/{version}/{CLAUDE_PLATFORM}/claude",
                                timeout=60) as resp, open(dest, "wb") as out:
        for chunk in iter(lambda: resp.read(1 << 20), b""):
            digest.update(chunk)
            out.write(chunk)
    if digest.hexdigest() != expected:
        raise SystemExit(f"Claude Code {version} checksum mismatch: got {digest.hexdigest()}, "
                         f"manifest says {expected}")
    os.chmod(dest, 0o755)
    with open(os.path.join(rootfs, CLAUDE_BAKE_DIR, "VERSION"), "w") as f:
        f.write(version + "\n")
    return version


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
        bake_claude(rootfs, args.claude_version)
        repackage(rootfs, args.output)
    finally:
        if owns_workdir:
            shutil.rmtree(workdir, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
