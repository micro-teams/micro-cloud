# templates/vm/debian13

A **Debian 13 + Docker Proxmox VM template**. The VM counterpart of `templates/lxc/debian13/`: same
OS and Docker, but provisioned as a full Proxmox **VM** (KVM) instead of an LXC container, using
**cloud-init** for per-machine setup.

Unlike the LXC template (a rootfs tarball built by `build.py`), a VM template is a *Proxmox VM
template* that MicroCloud **bakes on each placement** from the official Debian cloud image — there is
no packaged artifact and nothing to build in CI, only the two descriptors here.

## Layout

| path | what |
|---|---|
| `image-url` | One line: the http(s) URL of the base Debian 13 cloud image (`genericcloud` qcow2). MicroCloud downloads it to the placement's import storage as the starting point of the bake. |
| `build.sh` | The bake customization script. MicroCloud runs it **inside** a throwaway VM booted from the base image (over SSH, as `sudo bash -s`): installs Docker via Docker's apt repo and appends `docker` to the cloud-init default-user groups so every clone's login user is Docker-ready, then resets cloud-init. It does **not** power off — MicroCloud stops + templates the VM afterwards. |
| `init-machine.py` | Per-machine setup (**stage 2**), the VM counterpart of the LXC template's baked init. NOT baked into the image: after cloud-init creates the login user at clone, MicroCloud SSHes in as that user (with the operator key) and pipes this to `sudo python3 -` to install per-user software (Claude Code / AI tools), write the newapi relay config, and harden. Idempotent; `--ip` is not passed (cloud-init already set it). |

## Lifecycle (MicroCloud does all of this — see `TemplateUploader.bakeVm` / `MachineProvisioner.provisionVm`)

1. **Bake, once per placement** (when the template is "uploaded" to a placement):
   download `image-url` → `qm create` a throwaway VM importing it, with a cloud-init operator user +
   key + a leased temp IP → boot → SSH in and run `build.sh` → power off → `qm template`. The
   resulting template vmid is recorded on the upload row; the temp IP is released.
2. **Per machine — two stages:**
   1. **cloud-init (at clone):** `qm clone` the template → inject the login user + its SSH key + the
      operator key + a static IP (and cores / memory / disk) → boot. The machine is reachable as the
      login user the moment it boots, already in the `docker` group.
   2. **init-machine.py (after boot):** MicroCloud SSHes in as the login user (with the operator
      key) and pipes `init-machine.py` to `sudo python3 -` for per-user software install and final
      setup — the same step the LXC path runs, kept so dependencies (Claude Code, …) are installed
      *after* the user exists.

Machines are reachable on the private network only — **no public SSH port**. The static IP is
assigned by cloud-init at clone time (Proxmox `ipconfig0`), so the VM is reachable the moment it
boots without any fixed-IP handshake.
