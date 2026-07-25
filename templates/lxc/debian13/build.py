#!/usr/bin/env python3
"""Build the MicroCloud "Debian 13 + Claude Code" LXC template.

An LXC template is a compressed rootfs tarball. Rather than assemble one from scratch, this routine
DOWNLOADS the standard Debian 13 LXC template Proxmox ships (~124 MB `.tar.zst` from
download.proxmox.com), customizes it in a chroot (installs Claude Code and its deps, bakes in
init-machine.py, enables root SSH for first-boot init), and repackages it into our template tarball.

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
    p.add_argument("--claude-code-version", default="latest",
                   help="npm version spec for @anthropic-ai/claude-code")
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


def customize(rootfs: str, packages: list[str], claude_version: str) -> None:
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
# Node.js from NodeSource (a single lean package; Debian's own npm drags in a huge node-* tree).
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt-get install -y --no-install-recommends nodejs
npm install -g @anthropic-ai/claude-code@{claude_version}
# Trim: the npm package bundles per-platform binaries — drop the Windows exe (~260 MB) — and clear
# the npm cache, so the template stays close to the ~124 MB stock size.
find /usr/lib/node_modules -name '*.exe' -delete
npm cache clean --force || true
rm -rf /root/.npm /tmp/* /var/tmp/*
# Root may SSH into a freshly created machine only until init-machine.py hardens it away.
sed -i 's/^#*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
systemctl enable ssh || true
apt-get clean
rm -rf /var/lib/apt/lists/*
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
        customize(rootfs, read_packages(), args.claude_code_version)
        repackage(rootfs, args.output)
    finally:
        if owns_workdir:
            shutil.rmtree(workdir, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
