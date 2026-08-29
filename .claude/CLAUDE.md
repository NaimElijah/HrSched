# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

The application builds, tests, and runs. It is organised into **bounded contexts, each a
hexagon**, and both the hexagon and context independence are enforced by build-failing tests.

What it does not have is a domain. `scheduling` holds one walking-skeleton use case (schedule
a shift, read it back) that satisfies **no stated requirement**. `workforce` holds an identity
and a non-blank name, and deliberately precedes its requirements — it exists so the
context-independence rule is not vacuous (ADR-004). Neither is a feature.

Everything in `docs/requirements/requirements.md` from *Functional requirements* onward is
still `TODO`. A `TODO` there is a question for the repo owner, not a gap for an agent to fill
in. Do not invent a requirement, a domain rule, or a persistence choice to make a document
look complete.

Before extending the domain, Q2 (roles) and Q4 (working-time jurisdiction) need answers;
before adding persistence, ADR-006 needs writing. Adding a JPA starter would make ADR-006 by
accident — do not. Do not grow `workforce` to fill the gap either; it is small on purpose.

## Commands

All commands run **from `backend/`** and go through the committed Maven Wrapper. No global
Maven install is needed. The repository root builds nothing (ADR-005).

- Build + test + format + architecture (**the whole gate; what CI runs**): `./mvnw verify`
- Run: `./mvnw spring-boot:run` — serves on `http://localhost:8080`
- Test (all): `./mvnw test`
- Test (single class): `./mvnw test -Dtest=HexagonalArchitectureTest`
- Format check / fix: `./mvnw spotless:check` / `./mvnw spotless:apply`

On Windows without a POSIX shell, use `mvnw.cmd`. Add `-B` for non-interactive output.

## Layout

Repository root: `backend/`, `docs/`, `LICENSE`, `README.md`. `frontend/` will be a sibling of
`backend/` when a framework is chosen (ADR-005); it does not exist yet.

Inside `backend/src/main/java/io/github/naimelijah/hrsched/`, the package path *is* the
dependency rule — see ADR-003 and ADR-004. Every context repeats the same shape:

- `<context>/domain/model/` — entities and value objects. **JDK imports only.**
- `<context>/application/port/in/` — driving ports (use case interfaces) and commands.
- `<context>/application/port/out/` — driven ports (what the application needs outside).
- `<context>/application/service/` — use case implementations. **No Spring annotations.**
- `<context>/adapter/in/web/` — controllers, DTOs, exception translation. May use Spring.
- `<context>/adapter/out/persistence/` — driven port implementations. May use Spring.

Contexts today: `scheduling` (full hexagon), `workforce` (domain only). At the root beside
them sit `configuration/` (Spring wiring for every context) and `HrSchedApplication`.

There is no `shared/` package. Do not create one to solve a single duplication — a shared
kernel is a coupling and needs its own ADR.

- `docs/requirements/`, `docs/architecture/`, `docs/ADRs/` — what, how, and why.
- `.github/workflows/ci.yml` — runs `./mvnw verify` in `backend/`, nothing else.

## Conventions

- **Dependencies point inward, and contexts are independent. Both are enforced.**
  `HexagonalArchitectureTest` fails the build if the domain imports Spring, if the application
  touches an adapter, if one context references another, or if packages form a cycle. Do not
  weaken or delete a rule to make code pass — the rule is the point.
- **New code belongs to a context.** If it fits none of the existing ones, that is a new
  bounded context and a new ADR, not a new top-level package.
- **When adding an architecture rule, watch it fail first.** Every rule here has been observed
  failing on a deliberately introduced violation, then reverted. A rule that has only ever been
  green proves nothing.
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
