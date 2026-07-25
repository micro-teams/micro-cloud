#!/usr/bin/env bash
# init-machine.sh — MicroCloud Debian 13 LXC initialization (non-interactive, argument-driven).
#
# Ships inside the template at /root/init-machine.sh. MicroCloud runs it once on a freshly cloned
# container (preferably via `pct exec`, so root SSH need never be exposed) to create the non-root
# user, inject its SSH key, write the newapi relay config for Claude Code, and harden the box.
#
# Usage:
#   init-machine.sh --user <name> [--ssh-pubkey <key> | --password-stdin]
#                   [--anthropic-base-url <url> --anthropic-token <sk-...>]
#                   [--no-harden]
set -euo pipefail

[ "$EUID" -eq 0 ] || { echo "must run as root" >&2; exit 1; }

USER_NAME="" SSH_PUBKEY="" READ_PW=0 HARDEN=1
AI_BASE_URL="" AI_TOKEN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --user) USER_NAME="$2"; shift 2 ;;
    --ssh-pubkey) SSH_PUBKEY="$2"; shift 2 ;;
    --password-stdin) READ_PW=1; shift ;;               # password read from stdin, never a CLI arg
    --anthropic-base-url) AI_BASE_URL="$2"; shift 2 ;;
    --anthropic-token) AI_TOKEN="$2"; shift 2 ;;
    --no-harden) HARDEN=0; shift ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

[ -n "$USER_NAME" ] || { echo "--user is required" >&2; exit 1; }

if id "$USER_NAME" &>/dev/null; then
  echo "user '$USER_NAME' already exists, skipping creation"
else
  useradd -m -s /bin/bash "$USER_NAME"
fi

# Password (optional, only via stdin so it never lands in the process table).
if [ "$READ_PW" -eq 1 ]; then
  IFS= read -rs PW
  echo "${USER_NAME}:${PW}" | chpasswd
  unset PW
fi

# SSH public key (preferred — no password on the wire).
if [ -n "$SSH_PUBKEY" ]; then
  install -d -m700 -o "$USER_NAME" -g "$USER_NAME" "/home/$USER_NAME/.ssh"
  echo "$SSH_PUBKEY" > "/home/$USER_NAME/.ssh/authorized_keys"
  chmod 600 "/home/$USER_NAME/.ssh/authorized_keys"
  chown "$USER_NAME:$USER_NAME" "/home/$USER_NAME/.ssh/authorized_keys"
fi

# Passwordless sudo + docker group.
printf '%s ALL=(ALL) NOPASSWD:ALL\n' "$USER_NAME" > "/etc/sudoers.d/$USER_NAME"
chmod 0440 "/etc/sudoers.d/$USER_NAME"
if getent group docker >/dev/null; then
  usermod -aG docker "$USER_NAME"
fi

# newapi relay config, read by Claude Code through the login shell.
if [ -n "$AI_BASE_URL" ] && [ -n "$AI_TOKEN" ]; then
  umask 077
  cat > /etc/profile.d/microcloud-ai.sh <<EOF
export ANTHROPIC_BASE_URL="$AI_BASE_URL"
export ANTHROPIC_AUTH_TOKEN="$AI_TOKEN"
EOF
  install -d -m700 -o "$USER_NAME" -g "$USER_NAME" "/home/$USER_NAME/.claude"
  printf '{"hasCompletedOnboarding":true}\n' > "/home/$USER_NAME/.claude.json"
  chown "$USER_NAME:$USER_NAME" "/home/$USER_NAME/.claude.json"
fi

# Hardening: disable root SSH login, minimal firewall (SSH only).
if [ "$HARDEN" -eq 1 ]; then
  if [ -f /etc/ssh/sshd_config ]; then
    sed -i 's/^#*PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
    systemctl restart ssh 2>/dev/null || systemctl restart sshd 2>/dev/null || true
  fi
  if command -v ufw >/dev/null; then
    ufw default deny incoming >/dev/null
    ufw default allow outgoing >/dev/null
    ufw allow ssh >/dev/null
    echo y | ufw enable >/dev/null
  fi
fi

echo "initialized user '$USER_NAME' on $(hostname)"
