# CLAUDE.md

Guidance for agents (and humans) working in this repository.

## Commits

- Author every commit as the **project owner** (the real person who runs this repo), using their
  name and email — never as an agent/bot identity.
- Do **not** add a `Co-Authored-By: Claude <...>` (or any bot) trailer to commit messages.

## Required reading before integrating

MicroCloud is consumed by upstream services. Anyone integrating against it — or extending it — MUST
read, before writing code:

- [`business-model.md`](business-model.md) — the business model, concepts, and design rationale (English).
- [`业务模型.md`](业务模型.md) — the same, in Chinese.
- [`MicroCloud-API.yml`](MicroCloud-API.yml) — the OpenAPI contract. It is the single source of truth:
  the backend interfaces (`app.microteams.microcloud.api.*Api`) and the test frontend's client are
  both **generated** from it. Its per-operation comments document usage, including special cases
  (e.g. enumeration endpoints that require a mandatory scoping condition).

These documents explain the deliberate separation of physical reality (Proxmox clusters, placements,
networks) from the logical abstractions callers see (offerings, machine types, zones), which exists
to (1) keep the door open for future compute providers and (2) give callers a small, clear surface.
Skipping them leads to designs that leak Proxmox details or misuse the tenant abstractions.
