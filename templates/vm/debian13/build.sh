#!/usr/bin/env bash
#
# Bake step for the MicroCloud "Debian 13 + Docker" VM template.
#
# MicroCloud runs this INSIDE a throwaway VM it boots from the official Debian 13 cloud image (with a
# cloud-init operator user + key injected), over SSH, piped to `sudo bash -s`. It is the VM
# counterpart of the LXC template's build.py: it customizes the image so that once MicroCloud powers
# the VM off and runs `qm template`, every clone is Docker-ready and its cloud-init login user can
# use Docker without sudo. After this returns, MicroCloud stops the VM and templates it — this script
# does NOT power off.
#
# stdlib tools only (apt / sed / grep); no assumptions beyond a stock Debian cloud image.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

# The base cloud image's first-boot cloud-init runs its own apt (Proxmox sets package_upgrade), which
# holds the dpkg/apt lock. MicroCloud SSHes in as soon as TCP :22 is up — possibly while that apt is
# still running — so wait for cloud-init to finish before our own apt, or `apt-get` fails to take the
# lock ("Could not get lock /var/lib/apt/lists/lock"). `|| true`: a degraded/errored cloud-init still
# means its apt is done, and we'd rather proceed than abort the bake.
echo "[build] waiting for first-boot cloud-init to finish..."
cloud-init status --wait >/dev/null 2>&1 || true

# --- Docker, via Docker's official apt repository (the standard Debian install flow) ---
apt-get update
# ca-certificates+curl for the Docker repo below; tmux is required by the ccproxy subscription-login
# flow (it drives Claude Code inside a tmux session and does not install tmux itself).
apt-get install -y ca-certificates curl tmux
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
CODENAME="$(. /etc/os-release && echo "$VERSION_CODENAME")"
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $CODENAME stable" \
    > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable docker

# --- Base tools parity with the LXC template (templates/lxc/debian13/files/packages.txt) ---
# A stock Debian cloud image ships neither git nor tmux, so the same workload behaved differently
# depending on which offering it landed on. tmux is already installed above (ccproxy login needs it);
# git is added here so both offerings share the same baseline.
apt-get install -y git

# --- jujutsu (jj) ---
# A Git-compatible VCS not in Debian 13 stable. Install the official PREBUILT musl binary — it is
# self-contained, so NO Rust toolchain is needed on the machine (that would add hundreds of MB for no
# runtime benefit; anyone who wants Rust can install it themselves). The version is resolved from the
# releases/latest redirect (no GitHub API). KEEP THIS BLOCK IN SYNC WITH templates/lxc/debian13/build.py.
JJ_VER=$(curl -fsSLI -o /dev/null -w '%{url_effective}' https://github.com/jj-vcs/jj/releases/latest | sed 's#.*/tag/##')
JJ_TMP=$(mktemp -d)
curl -fsSL "https://github.com/jj-vcs/jj/releases/download/${JJ_VER}/jj-${JJ_VER}-x86_64-unknown-linux-musl.tar.gz" | tar -xz -C "$JJ_TMP"
install -m755 "$(find "$JJ_TMP" -name jj -type f | head -1)" /usr/local/bin/jj
rm -rf "$JJ_TMP"

# --- Claude Code, baked ---
# The binary only, fetched from the vendor's release layout and verified against the sha256 in
# its manifest; init-machine.py copies it into each login user's own install layout at first
# boot. The download it replaces cost every machine 49 s of boot and a route to claude.ai.
# KEEP IN SYNC WITH bake_claude() in templates/lxc/debian13/build.py.
CLAUDE_RELEASES=https://downloads.claude.ai/claude-code-releases
CLAUDE_PLATFORM=linux-x64
CLAUDE_VERSION="${CLAUDE_VERSION:-$(curl -fsSL "$CLAUDE_RELEASES/latest")}"
CLAUDE_SUM="$(curl -fsSL "$CLAUDE_RELEASES/$CLAUDE_VERSION/manifest.json" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['platforms']['$CLAUDE_PLATFORM']['checksum'])")"
install -d -m755 "/opt/claude/versions/$CLAUDE_VERSION"
curl -fsSL "$CLAUDE_RELEASES/$CLAUDE_VERSION/$CLAUDE_PLATFORM/claude" \
    -o "/opt/claude/versions/$CLAUDE_VERSION/claude"
echo "$CLAUDE_SUM  /opt/claude/versions/$CLAUDE_VERSION/claude" | sha256sum -c - >/dev/null
chmod 755 "/opt/claude/versions/$CLAUDE_VERSION/claude"
echo "$CLAUDE_VERSION" > /opt/claude/VERSION
echo "[build] baked Claude Code $CLAUDE_VERSION"

# --- Put every cloud-init-created login user in the docker group ---
# The Debian cloud image declares `system_info.default_user.groups` in a /etc/cloud/cloud.cfg.d
# drop-in that OVERRIDES the main /etc/cloud/cloud.cfg, so appending `docker` to the main file has no
# effect. Append it to whichever file actually sets the groups list (fallback: the main file). This
# is what makes each clone's login user land in the docker group on first boot.
target="$(grep -l 'groups:' /etc/cloud/cloud.cfg.d/*.cfg 2>/dev/null | head -1 || true)"
[ -n "$target" ] || target=/etc/cloud/cloud.cfg
if ! grep -qE '^[[:space:]]+groups:.*\bdocker\b' "$target"; then
    sed -i -E 's/^([[:space:]]+groups:[[:space:]]+)\[(.*)\]/\1[\2, docker]/' "$target"
fi
echo "[build] docker group appended in: $target"
grep -nE '^[[:space:]]+groups:' "$target" || true

# --- Reset cloud-init so the template runs it fresh on every clone (new user, key, static IP) ---
cloud-init clean --logs
apt-get clean
rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*
echo "[build] done"
