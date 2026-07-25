# MicroCloud

A base service that, given **Proxmox** and **newapi**, provisions machines with a working
**Claude Code**, makes them reachable over SSH on a private network, and bills each user for both
**machine compute** and **AI usage**. It is consumed by upstream services (Cheese, MicroTeams, …);
each has its own users, and MicroCloud owns the mapping from `(tenant, external user)` to accounts
and quota.

> Status: **skeleton**. This repo was seeded from the [MicroTeams](https://github.com/micro-teams/micro-teams)
> monorepo with its business logic stripped, the backend package renamed
> `app.microteams` → `app.microteams.microcloud`, and only a minimal authenticated `/ping` endpoint
> kept. The design lives in the team doc tree under `tech/microcloud/` and `design/microcloud/`.

## Architecture

```mermaid
flowchart TD
    caller(["upstream service<br/>Cheese / MicroTeams / test SPA"]) -->|"REST /microcloud<br/>(tenant secret, audited)"| nginx["nginx<br/>one origin"]
    nginx -->|"/"| frontend["test frontend<br/>React + Vite"]
    nginx -->|"/microcloud"| backend["backend<br/>Kotlin / Spring · :8080"]
    backend -->|"clone/exec LXC"| pve["Proxmox VE"]
    pve --> lxc["Debian 13 LXC<br/>Claude Code + SSH"]
    backend -->|"admin API:<br/>users / tokens / quota / logs"| newapi["newapi<br/>LLM relay · :3000"]
    lxc -->|"Claude-compatible inference"| newapi
    newapi -->|"upstream channels + keys"| up["real upstream models"]
    backend --> pg[("Postgres<br/>schema: microcloud")]
    user(["machine user"]) -->|"SSH (private net)"| lxc
```

- **nginx** — the one public origin; serves the SPA at `/` and proxies the backend at `/microcloud`.
- **backend ("microcloud")** — Kotlin / Spring Boot. Tenants, customers, accounts, machines,
  api-keys, billing, audit. Interfaces are **generated** from `MicroCloud-API.yml`.
- **newapi** — our fork [`micro-teams/new-api`](https://github.com/micro-teams/new-api) (release
  `v1.0.0-rc.21`): the LLM relay + per-key quota/metering. MicroCloud orchestrates its admin API.
- **Proxmox VE** — provisions the Debian 13 LXC (`images/lxc/debian13/`) with Claude Code installed.
- **Postgres** — MicroCloud's own schema `microcloud`.

## What is here

| | |
|---|---|
| **`MicroCloud-API.yml`** | The single API contract. Backend interfaces and the frontend client are both generated from it. |
| **`backend/`** | Kotlin / Spring Boot. The borrowed authorization framework keeps its `org.rucca.cheese.auth` package; everything else is `app.microteams.microcloud`. |
| **`frontend/`** | Minimal React + Vite test SPA — calls only the public API that upstreams also use (no extra backend). |
| **`images/lxc/debian13/`** | The v1 machine template: build recipe + `init-machine.sh` (parameterized, non-interactive). |
| **`deploy/`** | docker-compose (nginx + backend + newapi + postgres), bind-mounted stock images. |

## Build & run

```sh
cd backend && ./scripts/dependency-start.sh   # a local Postgres with the "microcloud" schema
./mvnw install                                # builds + runs integration tests
java -jar target/backend-0.1.0.jar
```

The whole cluster (with newapi): `cd deploy && bash gen-env.sh && docker compose up -d`.

## One contract, generated both ways

`MicroCloud-API.yml` is the source of truth: the backend regenerates `app.microteams.microcloud.api.*Api`
on every build (each path's first segment names its `Api` and its controller), and the frontend
regenerates its client. Change the API by editing the yaml first.
