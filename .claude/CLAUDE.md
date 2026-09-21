# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

The domain is modelled and works end to end: define people and shifts, configure working-time
rules, generate a roster, publish it. Three contexts — `scheduling`, `workforce`, `access` —
plus a `shared` kernel, with the hexagon and the context boundaries enforced by build-failing
tests.

Three things are **not** what they look like:

- **Generation is greedy, not optimised.** Automatic generation is the point of the product and
  a real implementation is a solver; `GreedyRosterGenerator` exists to prove the port (ADR-005).
  It ignores fairness, preferences and disruption entirely.
- **Authentication is half-migrated.** HrSched holds its own accounts and signs its own tokens
  (ADR-011, ADR-028), and `/api/auth/**`, `/api/organisations` and `/api/accounts` take the acting
  identity from the access token. **Every other route still reads `X-User`, `X-Role`,
  `X-Organisation` and `X-Employee`, which any caller can write.** That placeholder is being
  deleted one route family at a time; do not extend it, and do not add a route that reads it.
- **The audit trail is a log stream, not a trail.** Every change and refusal is recorded through
  an `AuditTrail` driven port, but nothing is stored and nothing is queryable: the record lives
  only wherever stdout is collected. Whether an Admin may read it, and for how long it is kept,
  is Q20 (ADR-018).

Everything in `docs/requirements/requirements.md` from *Functional requirements* onward is
still `TODO`. A `TODO` there is a question for the repo owner, not a gap for an agent to fill
in. Do not invent a requirement, a domain rule, or a persistence choice to make a document
look complete.

Every question that blocked modelling is answered — see `docs/requirements/requirements.md`.
Three consequences bind everything written from now on:

- **Multi-tenant.** Every aggregate belongs to exactly one organisation and every query is
  scoped to it. A cross-tenant read is a security incident, not a bug.
- **No legal threshold is ever hardcoded.** Working-time rules are a *configured constraint
  set*; a jurisdiction is a preset, and a preset's numbers must come from the statute with the
  source cited. Never write a rest period or an hours cap as a constant.
- **Constraints are invariants.** A roster violating its constraint set cannot be published,
  and there is no override.

Persistence is settled (ADR-004): PostgreSQL, hand-written SQL through `JdbcClient`, Flyway
migrations, one schema with `organisation_id` on every aggregate root (child tables are reached
only through their root). **Do not add an ORM.** The domain may
not carry `jakarta.persistence` annotations — the architecture test fails on them — so JPA would
need a parallel annotated model and would buy only behaviour a hexagonal adapter must not leak.

**Docker must be running to build.** The tests use a real PostgreSQL through Testcontainers, one
container shared by the suite. There is no in-memory fallback and no skip-when-Docker-is-absent, on
purpose: a check that quietly does not run still reports green.

**Every use case is one transaction**, wrapped in `UseCaseConfiguration` rather than annotated,
because the architecture test forbids Spring in the application layer (ADR-023). A new use case gets
that for free by being registered there — which is already mandatory.

**No number from any statute belongs in a migration either.** The schema stores a working-time
limit as a value; it never defaults one.

## Commands

Two buildable projects: `backend/` (Maven Wrapper, no global Maven needed) and `frontend/`
(npm). The repository root builds nothing itself but orchestrates both (ADR-014).

From the **root**: `npm run setup` · `npm run dev` · `npm run verify` (runs both gates).

- Build + test + format + architecture (**the whole gate; what CI runs**): `./mvnw verify`
- Run: `./mvnw spring-boot:run` — serves on `http://localhost:8080`
- Test (all): `./mvnw test`
- Test (single class): `./mvnw test -Dtest=HexagonalArchitectureTest`
- Format check / fix: `./mvnw spotless:check` / `./mvnw spotless:apply`
- Coverage: written by `./mvnw verify` to `target/site/jacoco/index.html`. `./mvnw test` alone
  writes none — the report goal binds to `verify`. Nothing fails on it (ADR-015).

From `frontend/`:

- Everything CI runs: `npm run verify` — format check, lint, typecheck, tests, production build
- Dev server: `npm run dev` — serves on 5173 and proxies `/api` to 8080
- One check at a time: `npm run lint` / `npm run typecheck` / `npm test`
- Coverage: `npm run test:coverage` → `coverage/index.html`. Deliberately not part of `verify`.

On Windows without a POSIX shell, use `mvnw.cmd`; it is a batch/PowerShell polyglot, so PowerShell
must be on `PATH`. Add `-B` for non-interactive output.

## Layout

Repository root: `backend/`, `frontend/`, `docs/`, `scripts/`, `package.json`, `LICENSE`,
`README.md`. The two projects build independently (ADR-007); `scripts/run.mjs` only orchestrates
and must never accumulate build logic (ADR-014).

Inside `backend/src/main/java/io/github/naimelijah/hrsched/`, the package path *is* the
dependency rule — see ADR-003 and ADR-006. Every context repeats the same shape:

- `shared/domain/model/` — the kernel: identities (`OrganisationId`, `EmployeeId`,
  `QualificationId`, `UserId`), `TimeRange`, and the sealed `Actor`. Depends on no context, and
  must stay that way. Do not put behaviour here.
- `<context>/domain/model/` — entities and value objects. **JDK imports only.**
- `<context>/application/port/in/` — driving ports (use case interfaces) and commands.
- `<context>/application/port/out/` — driven ports (what the application needs outside).
- `<context>/application/service/` — use case implementations. **No Spring annotations.**
- `<context>/adapter/in/web/` — controllers, DTOs, exception translation. May use Spring.
- `<context>/adapter/out/persistence/` — driven port implementations. May use Spring. Each
  `Jdbc*Repository` owns its own SQL; there is deliberately no shared helper package, because
  `ContextPackages` would treat a new top-level package as a bounded context and every context
  touching it would fail the cross-context rule.

Contexts today: `scheduling` (shifts, rosters, constraints, generation), `workforce` (employees,
qualifications, availability), `access` (organisations). At the root beside them sit
`configuration/` and `HrSchedApplication`.

A context may reference another context's `application.port.in` package and **nothing else**
(ADR-008). Consuming one belongs in an adapter — see
`scheduling/adapter/out/workforce/WorkforceDirectoryAdapter`, the only class in scheduling that
names another context.

`frontend/` is a React 19 SPA built by Vite (ADR-013):

- `src/api/` — the typed client. `client.ts` is the **only** module that calls `fetch` or names a
  header; `problem.ts` validates the error body rather than casting it; `scheduling.ts`, `workforce.ts`,
  `access.ts` and `notifications.ts` mirror the backend DTOs, one module per context.
- `src/auth/` — `SessionProvider` and the sign-in, change-password and session-bar screens. It
  replaced `src/identity/`, the acting-as placeholder, when sign-in landed (ADR-011, ADR-028).
- `src/pages/` — one screen per capability. Pages never call `fetch` directly.

Also inside `backend/`: `src/main/resources/db/migration/` holds the Flyway migrations — the only
way the schema changes — and `compose.yaml` holds the local database, beside the backend rather
than at the root because Boot looks in the working directory.

Outside `backend/`:

- `docs/requirements/`, `docs/architecture/`, `docs/ADRs/` — what, how, and why.
- `.github/workflows/ci.yml` — two jobs, backend and frontend, each running that project's own
  verify command. **No path filters:** a wrongly filtered check is skipped silently and still
  reports green.

## Conventions

- **Dependencies point inward, and contexts are independent. Both are enforced.**
  `HexagonalArchitectureTest` fails the build if the domain imports Spring, if the application
  touches an adapter, if a context reaches past another context's published ports, if the kernel
  depends on a context, or if a context's own layers form a cycle. Do not weaken or delete a rule
  to make code pass — the rule is the point. Deleting one because it is provably unable to fail
  is a different thing, and needs the proof.
- **NEVER write a working-time threshold as a constant.** No number from any statute belongs in
  any class. A minimum rest period is a value in a `ConstraintSet`, supplied as configuration. A
  jurisdiction is a preset whose numbers must be sourced from the statute and cited. Test values
  are invented on purpose, and are labelled as such.
- **Tenancy is in every signature.** Repository lookups take an `OrganisationId`; there is no
  `findById(EmployeeId)`. Use cases take an `Actor` and call `requireAccessTo` first. A
  cross-tenant attempt is a 404, never a 403 — a 403 confirms the resource exists.
- **`UserId` and `EmployeeId` are never compared.** They are different identities on purpose. To
  ask whether an actor is the person being acted on, use `TenantActor.isThemself` — the link is
  carried, not inferred from matching UUIDs (ADR-012).
- **Only a Scheduler builds rosters.** `MAY_SCHEDULE` is `{SCHEDULER}`; an Admin manages people,
  qualifications and the constraint set. The role javadoc and the requirements table agree, and
  the permission sets must follow them rather than the other way round.
- **Never let unverifiable data pass a check.** If evidence a rule needs cannot be loaded, refuse
  — a skipped assignment is a roster published with hours nobody counted.
- **New code belongs to a context.** If it fits none of the existing ones, that is a new
  bounded context and a new ADR, not a new top-level package.
- **`@ParameterizedTest` is an accepted idiom, for data-only rows.** `junit-jupiter-params` comes
  with the Boot BOM; no dependency to add. Use it where the rows differ only in *data* and the
  assertion sentence is identical — null-check batteries, blank-name batteries, the identity
  records, exception-type lists, and *parsing* a role or header string — `ActorFromHeadersTest`
  tables its role strings and is right to. Do **not** use it for a permission or tenancy
  **decision**: who may do what, and who may see whose data. A parameterised isolation test reads
  as a table and hides which row is the one whose failure is a security incident. Nor for rows
  that differ in setup, or whose comment explains *why* — the comment then belongs to no row. If a
  table passes about a dozen rows, split it.
- **Instrumentation lives in adapters, and three rules enforce it.** Nothing prints anywhere; the
  domain may not touch `org.slf4j`, `ch.qos.logback` or `java.util.logging`; and neither the domain
  nor the application layer may touch `io.micrometer` or `io.opentelemetry`. So Micrometer's
  `@Timed` and `@Counted` cannot go on a use case, and a service that needs to record something
  records it through a driven port. See ADR-016 to ADR-018.
- **NEVER log personal data.** Names, absence and contracted hours say things about a person's
  health and household, and a log is copied to places a database is not. `AuditedChange` carries a
  bare `UUID` subject so a name is *unrepresentable*, not merely discouraged, and
  `SchedulingAuditLogTest`/`WorkforceAuditLogTest` assert every field is an identifier or an enum.
  Attach fields with slf4j `addKeyValue`, never by interpolating them into the message: the message
  is a constant, and everything variable is a field the ECS encoder publishes as JSON.
- **The audit port is per context on purpose.** `scheduling` and `workforce` each own an
  `AuditTrail` and an `AuditedChange` with their own change enum. They are different types, not
  duplication, and anything shared would have to enter the kernel ADR-008 keeps small. The
  collision shows up only in `UseCaseConfiguration`, which writes one of them out in full.
- **Recording happens after the save, and a refused act records nothing.** An entry for something
  that then failed to store is a record of something that did not happen. `publish` records inside
  the supersede loop as well as for itself — the rosters it demotes did not demote themselves.
- **Two `@Component` classes may not share a simple name.** Spring derives a bean name from it
  alone, so identically-named adapters in two contexts collide, and they collide at startup rather
  than at compile time. Hence `SchedulingAuditLog` and `WorkforceAuditLog`.
- **Boot disables metrics export under test.** `/actuator/prometheus` answers 404 in a
  `@SpringBootTest` while working in production; `@AutoConfigureMetrics` opts back in, at the cost
  of a second Spring context. `MetricsHttpTest` is the only test here that does not share the one
  the others use.
- **When adding an architecture rule, watch it fail first.** All nine rules here have been
  observed failing on a deliberately introduced violation, then reverted. A rule that has only
  ever been green proves nothing — one rule was deleted after it turned out it could no longer
  fail, being fully subsumed by what is now
  `contexts_touch_only_published_ports_of_other_contexts`. The same applies to a test: a fix
  whose test was never seen failing has not been shown to fix anything.
- **Adding a use case:** pick the context → domain concept → port in/out → service (no
  annotation) → register in `UseCaseConfiguration` → adapters → tests. Forgetting the bean
  registration fails at startup, not at compile time.
- **Entities vs value objects.** `Shift` and `Employee` are classes with identity equality,
  because two shifts with the same period — or two people with the same name — are still
  different things. `ShiftId`, `ShiftPeriod` and `EmployeeId` are records because they *are*
  their values. Do not turn an entity into a record.
- **Invariants live in constructors,** so illegal states cannot be represented and callers
  never have to check.
- **The formatter is authoritative.** google-java-format is not configurable — 2-space indent,
  fixed wrapping. Never hand-format; run `./mvnw spotless:apply`.
- **The UI enforces nothing, and must not pretend to.** Every rule is the backend's. A screen that
  looks like it satisfies a requirement is not evidence the rule holds, which is why the
  `Enforced by` column in `requirements.md` names only backend classes and tests.
- **Never put a suggested working-time number in the UI.** A default in a form field is a legal
  threshold chosen by a developer, which is the exact thing this codebase refuses to contain.
- **Frontend versions are ahead of most published guidance.** React 19, Vite 8, TypeScript 6,
  ESLint 10, Vitest 4. Check the installed package before trusting a recalled API — the same
  discipline the Spring Boot 4.x renames forced. Already found this way: Vitest 4 moves the `test`
  key off Vite's config type (import `defineConfig` from `vitest/config`), and
  `eslint-plugin-react-hooks` 7 exposes flat config under `configs.flat`.
- **`pom.xml` is indented with tabs** (Spring Initializr's convention) while Java is 2-space.
  `.editorconfig` encodes both; this is deliberate, not drift.

## Spring Boot 4.x renamed things

Training data and nearly every tutorial still use the 3.x names, which will not compile.
Verified against the jars in this project:

| 3.x | 4.1.1 |
| --- | --- |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `spring-boot-starter-test` | split: `spring-boot-starter-webmvc-test`, `-actuator-test`, `-jackson-test` |
| `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| `@MockBean` | `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`) |
| `com.fasterxml.jackson.databind` | `tools.jackson.databind` (Jackson 3) |

When unsure, inspect the jar (`unzip -l ~/.m2/repository/...`) or run `./mvnw dependency:tree`.
Do not guess.
