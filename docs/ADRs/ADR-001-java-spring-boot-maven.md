# ADR-001: Build HrSched on Java 21, Spring Boot, and Maven

- **Status:** Accepted
  - **Amended 2026-10-30:** "Spring Boot 4.1.1" has moved. The version in use is the parent in
    `backend/pom.xml`.
- **Date:** 2026-08-29

## Context

HrSched is greenfield: before this decision the repository held documentation and no code,
and every command in the README was blocked on the stack being chosen.

The forces:

- **One developer, one machine.** The development machine already has JDK 21.0.9 (LTS) and
  Maven 3.9.6 installed, and does **not** have Gradle or `make`. A stack that matches what
  is installed costs nothing to start.
- **The domain is constraint-heavy, not throughput-heavy.** Rostering is about evaluating
  rules over a modest data set, not about handling extreme load. Ecosystem maturity and
  library availability matter more than raw runtime performance.
- **The project baseline demands health and readiness endpoints, structured logging, and
  vendor-neutral instrumentation** from every service. A framework that supplies those
  without bespoke code removes a whole class of work.
- **Nothing about the domain is settled yet** (see `docs/requirements/requirements.md`), so
  the stack must not foreclose the storage decision, which is deliberately deferred.

## Decision

We build HrSched as a **Java 21 (LTS)** application on **Spring Boot 4.1.1**, built with
**Maven** through the committed Maven Wrapper (`./mvnw`).

`./mvnw` is the single entry point for setup, run, and test. There is no `Makefile`.

The initial dependency set is deliberately minimal — `spring-boot-starter-webmvc` and
`spring-boot-starter-actuator`, plus their test starters. Notably **no persistence starter**:
storage is ADR-004 and is not yet decided, and adding JPA now would quietly make that
decision by default.

## Consequences

**Easier**

- Health and readiness endpoints exist immediately via Actuator, satisfying a baseline
  requirement with configuration rather than code.
- One command, `./mvnw verify`, builds, tests, and format-checks — locally and in CI
  identically, with no `make` dependency on a Windows machine.
- The Maven Wrapper pins the build tool version in the repository, so the toolchain is
  checked in rather than assumed.

**Harder**

- **Spring Boot 4.x is recent and renamed things.** The web starter is
  `spring-boot-starter-webmvc`, not `spring-boot-starter-web`, and the single
  `spring-boot-starter-test` has been split into per-concern test starters
  (`spring-boot-starter-webmvc-test`, `spring-boot-starter-actuator-test`). Most tutorials,
  answers, and language-model recall still describe the Boot 3.x names. Copy-pasted advice
  will not compile; check the generated `pom.xml`, not the internet.
- Java 21 rather than 25 or 26 means no access to the newest language features until the
  local JDK is upgraded.
- Spring's runtime magic (auto-configuration, classpath scanning) makes it easy to let
  framework types leak into the domain. Preventing that is a discipline the framework does
  not enforce — see ADR-003 when it is written.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Gradle** instead of Maven | Not installed on the development machine, while Maven is. Gradle's advantages (incremental builds, faster large builds) address a problem this project does not have at its current size. **Revisit if** a clean `./mvnw verify` exceeds roughly two minutes, or the build becomes genuinely multi-module. |
| **Java 25 or 26** | The installed JDK is 21 LTS, and Boot 4.1.1 supports it. Choosing a JDK that is not on the machine buys unused language features in exchange for an install and a CI/local mismatch. **Revisit when** the local JDK is upgraded, or a needed feature lands in a newer release. |
| **A `Makefile` as the entry point** | `make` is not installed on the Windows development machine, so the "one command" would fail for its only user. `./mvnw` is cross-platform and already committed. **Revisit if** the project gains non-Maven tasks (container builds, infrastructure) that need orchestrating. |
| **Adding a persistence starter now** | Would make the storage decision (ADR-004) silently and by default, before any requirement justifies a particular store. **Revisit when** ADR-004 is written. |
