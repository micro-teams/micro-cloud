# Deploy

Self-contained bundle: stock official images (nginx / JRE / postgres / newapi) with this project's
build artifacts **bind-mounted** in. No custom images to build or pull from a registry.

```
docker-compose.yml     four services (nginx, backend, newapi, postgres)
nginx.conf             domain-independent gateway (SPA + /microcloud -> backend)
gen-env.sh             generates .env with random secrets + app_data/
init/                  postgres first-init SQL (creates the "microcloud" schema)
backend/backend.jar    the backend (CI fills this in the shipped bundle)
frontend/dist/         built test SPA (CI fills this in the shipped bundle)
app_data/              not shipped; gen-env.sh creates it; all persistent state lives here
```

## Three steps

```bash
bash gen-env.sh          # once: writes .env (random secrets) and app_data/
docker compose up -d
docker compose ps        # wait for every service 'healthy'; nginx listens on :80
```

## Domain-independent

The backend derives its own public URL from `X-Forwarded-Proto`/`X-Forwarded-Host`, so the same
bundle works behind any domain. Put your own TLS-terminating reverse proxy (Caddy / nginx / cloud LB
/ Cloudflare Tunnel) in front of port 80 and forward those headers.

## newapi

The LLM relay runs as the `newapi` service — our fork `micro-teams/new-api`, built and published to
`ghcr.io/micro-teams/new-api:v1.0.0-rc.21`. It stores state under `app_data/newapi/`. Configure its
upstream channels + admin token on first boot (see `tech/microcloud/04-newapi-relay.md` in the team
docs).
