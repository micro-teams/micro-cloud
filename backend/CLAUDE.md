# backend (microcloud) — conventions

Kotlin / Spring Boot 3.4 / Java 21 / Maven. Seeded from MicroTeams' backend with business logic
removed; the conventions below are inherited and still authoritative.

- **One contract, generated.** `../MicroCloud-API.yml` is the source of truth. `./mvnw` regenerates
  `app.microteams.microcloud.api.*Api` + `app.microteams.microcloud.model.*DTO` (openapi-generator,
  copied into `src/` by the antrun `copy-generated-api` step — **do not edit generated files**). The
  generator names an `Api` after a path's first segment; each module's single controller implements
  exactly its own interface.
- **Packages.** MicroCloud code is `app.microteams.microcloud.*`. The borrowed authorization
  framework and its leaf kernel keep their original `org.rucca.cheese.{auth,common}` packages (so
  they can one day be extracted); the component scan covers both roots.
- **Authorization is all in one place.** `org.rucca.cheese.auth` (`@Guard(action, resourceType)` +
  `AuthorizationAspect` + custom-logic expressions) is the whole model. The concrete rules live in
  `app.microteams.microcloud.authz.RolePermissionService` — grants per (role, resource), no
  authorization in business code. It grants the super-admin and tenant roles their full permission
  sets (tenant/customer/account/machine/offering/placement/…); a machine's AI-mode switch is a
  tenant grant scoped to owned machines.
- **Persistence.** Postgres, schema `microcloud` (`hibernate.default_schema`). Entities extend
  `BaseEntity` (SEQUENCE id + created/updated/deletedAt), soft-delete via `@SQLRestriction`, enums as
  strings, and rely on the `all-open` compiler plugin (never make entities `data class`).
- **Errors.** Throw `BaseError` subclasses; the global handler maps them. Success responses are bare
  DTOs.
- **Tests are integration tests** against a real Postgres (`./scripts/dependency-start.sh`).
- Every Kotlin file starts with a `/* Description / Author(s) */` header; code + comments in English;
  spotless(ktfmt) enforced (`./mvnw spotless:apply`).
