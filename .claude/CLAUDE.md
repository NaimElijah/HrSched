# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

The application **builds, tests, and runs**, but implements **no rostering logic**. It is a
Spring Boot composition root plus health probes. Everything in
`docs/requirements/requirements.md` from *Functional requirements* onward is still `TODO`.

A `TODO` in the requirements is a question for the repo owner, not a gap for an agent to
fill in. Do not invent a requirement, a domain rule, or a persistence choice to make a
document look complete.

Before adding a domain module, the module boundaries (ADR-003) and the storage strategy
(ADR-004) have to be decided. Neither is written. Adding a persistence starter would make
ADR-004 by accident — do not.

## Commands

All commands go through the committed Maven Wrapper. No global Maven install is needed.

- Build + test + format check (**the whole gate; what CI runs**): `./mvnw verify`
- Run: `./mvnw spring-boot:run` — serves on `http://localhost:8080`
- Test (all): `./mvnw test`
- Test (single class): `./mvnw test -Dtest=HrSchedApplicationTests`
- Format check: `./mvnw spotless:check`
- Format fix: `./mvnw spotless:apply`

On Windows without a POSIX shell, use `mvnw.cmd`. Add `-B` for non-interactive output.

## Layout

- `src/main/java/io/github/naimelijah/hrsched/` — application code. Only the composition
  root exists. There is **no domain package yet**.
- `src/test/java/…` — tests. Naming: `Given<scenario>_When<action>_Then<result>`.
- `docs/requirements/requirements.md` — what the system must do. Intent, not observation.
- `docs/architecture/architecture.md` — the system as it actually is. Descriptive, never
  aspirational.
- `docs/ADRs/` — one file per decision, `ADR-NNN-short-kebab-title.md`, append-only.
  `README.md` is the index and the list of decisions still owed; `adr-template.md` is the
  starting point for a new record.
- `.github/workflows/ci.yml` — runs `./mvnw verify`, nothing else.
- `.claude/skills/`, `.claude/agents/`, `.claude/rules/` — agent configuration, tracked in git.

## Conventions

- **Spring Boot 4.x renamed starters.** It is `spring-boot-starter-webmvc`, not
  `spring-boot-starter-web`, and the old `spring-boot-starter-test` is split into
  `spring-boot-starter-webmvc-test` and `spring-boot-starter-actuator-test`. Training data
  and most tutorials still use the 3.x names and will not compile. Read `pom.xml`.
- **The formatter is authoritative.** google-java-format is not configurable — 2-space
  indent, fixed wrapping. Never hand-format; run `./mvnw spotless:apply`. Spotless is bound
  to `validate`, so a formatting slip fails the build before tests run.
- **`pom.xml` is indented with tabs** (Spring Initializr's convention) while Java is
  2-space. `.editorconfig` encodes both; this is deliberate, not drift.
- **The composition root holds no logic.** `HrSchedApplication` starts Spring and nothing
  else, so the domain stays testable without booting the framework. Keep it that way.
- **Dependency direction is intended but not enforced.** Nothing in the build stops a domain
  class importing Spring. Until ADR-003 lands and an enforcement test exists, this is a
  review responsibility.
