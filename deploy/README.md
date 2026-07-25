# Deploy

Self-contained bundle: stock official images (nginx / JRE / postgres) plus our own newapi image
(pulled from GHCR), with this project's build artifacts **bind-mounted** in.

```
docker-compose.yml     four services (nginx, backend, newapi, postgres)
nginx.conf             domain-independent gateway (SPA + /microcloud -> backend)
gen-env.sh             generates .env with random secrets + app_data/
init/                  postgres first-init SQL (creates the "microcloud" schema)
backend/backend.jar    the backend (CI fills this in the shipped bundle)
frontend/dist/         built test SPA (CI fills this in the shipped bundle)
templates/lxc/debian13/  the built Debian 13 LXC template (~150 MB .tar.zst) + init-machine.py — a
                       core part of the system, used to provision machines on Proxmox. build.py (the
                       compile step) produced the image and does NOT ship here.
app_data/              not shipped; gen-env.sh creates it; all persistent state lives here
```

## Three steps

```bash
bash gen-env.sh          # once: writes .env (random secrets) and app_data/
docker compose up -d     # pulls stock images + ghcr.io/micro-teams/new-api
docker compose ps        # wait for every service 'healthy'; nginx listens on :80
```

## Domain-independent

The backend derives its own public URL from `X-Forwarded-Proto`/`X-Forwarded-Host`, so the same
bundle works behind any domain. Put your own TLS-terminating reverse proxy (Caddy / nginx / cloud LB
/ Cloudflare Tunnel) in front of port 80 and forward those headers.

## newapi

The LLM relay runs as the `newapi` service — our fork `micro-teams/new-api`, built and published to
the **public** image `ghcr.io/micro-teams/new-api:v1.0.0-rc.21`, which compose pulls at deploy time
(no registry credentials needed). It stores state under `app_data/newapi/`. Configure its upstream
channels + admin token on first boot (see `tech/microcloud/04-newapi-relay.md` in the team docs).

## The LXC template

`templates/lxc/debian13/debian13-microcloud.tar.zst` is our Debian 13 + Claude Code LXC template,
built in CI by `build.py` and shipped here because it is core to provisioning. Upload it to Proxmox
(`pveam`/storage) so the backend can create containers from it; `init-machine.py` (also here) is run
on each new container to initialize it.
