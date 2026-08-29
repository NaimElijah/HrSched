# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

The application builds, tests, and runs, and the **hexagonal structure is in place and
enforced**. What it does not have is a domain: one walking-skeleton use case exists (schedule
a shift, read it back) which satisfies **no stated requirement** and is scaffolding, not a
feature.

Everything in `docs/requirements/requirements.md` from *Functional requirements* onward is
still `TODO`. A `TODO` there is a question for the repo owner, not a gap for an agent to fill
in. Do not invent a requirement, a domain rule, or a persistence choice to make a document
look complete.

Before extending the domain, Q2 (roles) and Q4 (working-time jurisdiction) need answers;
before adding persistence, ADR-004 needs writing. Adding a JPA starter would make ADR-004 by
accident — do not.

## Commands

All commands go through the committed Maven Wrapper. No global Maven install is needed.

- Build + test + format + architecture (**the whole gate; what CI runs**): `./mvnw verify`
- Run: `./mvnw spring-boot:run` — serves on `http://localhost:8080`
- Test (all): `./mvnw test`
- Test (single class): `./mvnw test -Dtest=HexagonalArchitectureTest`
- Format check / fix: `./mvnw spotless:check` / `./mvnw spotless:apply`

On Windows without a POSIX shell, use `mvnw.cmd`. Add `-B` for non-interactive output.

## Layout

The package path *is* the dependency rule — see ADR-003.

- `domain/model/` — entities and value objects. **JDK imports only.**
- `application/port/in/` — driving ports (use case interfaces) and commands.
- `application/port/out/` — driven ports (what the application needs from the outside).
- `application/service/` — use case implementations. **No Spring annotations.**
- `adapter/in/web/` — controllers, DTOs, exception translation. May use Spring.
- `adapter/out/persistence/` — driven port implementations. May use Spring.
- `configuration/` — Spring wiring for the framework-free layers.
- `docs/requirements/`, `docs/architecture/`, `docs/ADRs/` — what, how, and why.
- `.github/workflows/ci.yml` — runs `./mvnw verify`, nothing else.

## Conventions

- **Dependencies point inward, and this is enforced.** `HexagonalArchitectureTest` fails the
  build if the domain imports Spring, if the application touches an adapter, or if packages
  form a cycle. Do not weaken or delete a rule to make code pass — the rule is the point.
- **Adding a use case:** domain concept → port in/out → service (no annotation) → register in
  `UseCaseConfiguration` → adapters → tests. Forgetting the bean registration fails at
  startup, not at compile time.
- **Entities vs value objects.** `Shift` is a class with identity equality because two shifts
  with the same period are different shifts. `ShiftId` and `ShiftPeriod` are records because
  they are their values. Do not turn an entity into a record.
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
