#!/usr/bin/env bash
# build-template.sh — build the MicroCloud "Debian 13 + Claude Code" LXC template on a Proxmox node.
#
# v1's only machine form. Produces a Proxmox template that MicroCloud clones per machine; the clone
# is then finished by /root/init-machine.sh (see this directory). Run this ON a PVE node as root.
#
# This is the skeleton recipe — the exact base image id, storage, and network are deployment
# specifics to fill in when we wire up the real Proxmox endpoint. It is intentionally explicit and
# idempotent-ish rather than clever.
set -euo pipefail

VMID="${VMID:-9000}"                       # template vmid
STORAGE="${STORAGE:-local-lvm}"            # rootfs storage
TEMPLATE_STORAGE="${TEMPLATE_STORAGE:-local}"
BASE="${BASE:-debian-13-standard}"        # `pveam available | grep debian-13` for the exact name
HOSTNAME="${HOSTNAME:-microcloud-debian13}"

echo "==> downloading base template ($BASE) if needed"
pveam update
ARCHIVE="$(pveam available --section system | awk -v b="$BASE" '$2 ~ b {print $2}' | tail -n1)"
[ -n "$ARCHIVE" ] || { echo "could not find a $BASE archive in 'pveam available'"; exit 1; }
pveam download "$TEMPLATE_STORAGE" "$ARCHIVE" || true

echo "==> creating container $VMID"
pct create "$VMID" "$TEMPLATE_STORAGE:vztmpl/$ARCHIVE" \
  --hostname "$HOSTNAME" \
  --cores 2 --memory 2048 --swap 512 \
  --rootfs "$STORAGE:8" \
  --features nesting=1 \
  --unprivileged 1 \
  --net0 name=eth0,bridge=vmbr0,ip=dhcp \
  --ssh-public-keys /dev/null 2>/dev/null || true

pct start "$VMID"
sleep 5

echo "==> installing packages inside the container"
pct exec "$VMID" -- bash -lc '
  set -e
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y curl git sudo ufw openssh-server ca-certificates nodejs npm docker.io
  npm install -g @anthropic-ai/claude-code
  # Root may SSH into a freshly cloned machine only until init-machine.sh hardens it away.
  sed -i "s/^#*PermitRootLogin.*/PermitRootLogin yes/" /etc/ssh/sshd_config
  systemctl enable ssh
'

echo "==> installing /root/init-machine.sh"
pct push "$VMID" "$(dirname "$0")/init-machine.sh" /root/init-machine.sh --perms 0755

echo "==> converting $VMID into a template"
pct stop "$VMID"
pct template "$VMID"
echo "done: template $VMID ($HOSTNAME). MicroCloud clones this per machine."
