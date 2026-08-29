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
- **There is no authentication.** The acting identity comes from request headers, so any caller
  can claim any role in any organisation. It is a placeholder to be deleted, not extended.
- **Nothing survives a restart.** Every repository is in memory until ADR-004.

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

Before adding persistence, ADR-004 needs writing. Adding a JPA starter would make ADR-004 by
accident — do not.

## Commands

All commands run **from `backend/`** and go through the committed Maven Wrapper. No global
Maven install is needed. The repository root builds nothing (ADR-007).

- Build + test + format + architecture (**the whole gate; what CI runs**): `./mvnw verify`
- Run: `./mvnw spring-boot:run` — serves on `http://localhost:8080`
- Test (all): `./mvnw test`
- Test (single class): `./mvnw test -Dtest=HexagonalArchitectureTest`
- Format check / fix: `./mvnw spotless:check` / `./mvnw spotless:apply`

On Windows without a POSIX shell, use `mvnw.cmd`. Add `-B` for non-interactive output.

## Layout

Repository root: `backend/`, `docs/`, `LICENSE`, `README.md`. `frontend/` will be a sibling of
`backend/` when a framework is chosen (ADR-007); it does not exist yet.

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
- `<context>/adapter/out/persistence/` — driven port implementations. May use Spring.

Contexts today: `scheduling` (shifts, rosters, constraints, generation), `workforce` (employees,
qualifications, availability), `access` (organisations). At the root beside them sit
`configuration/` and `HrSchedApplication`.

A context may reference another context's `application.port.in` package and **nothing else**
(ADR-008). Consuming one belongs in an adapter — see
`scheduling/adapter/out/workforce/WorkforceDirectoryAdapter`, the only class in scheduling that
names another context.

Outside `backend/`:

- `docs/requirements/`, `docs/architecture/`, `docs/ADRs/` — what, how, and why.
- `.github/workflows/ci.yml` — runs `./mvnw verify` in `backend/`, nothing else.

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
- **New code belongs to a context.** If it fits none of the existing ones, that is a new
  bounded context and a new ADR, not a new top-level package.
- **When adding an architecture rule, watch it fail first.** All five rules here have been
  observed failing on a deliberately introduced violation, then reverted. A rule that has only
  ever been green proves nothing — one rule was deleted after it turned out it could no longer
  fail, being fully subsumed by `contexts_do_not_depend_on_each_other`.
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
