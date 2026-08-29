# Architecture — HrSched

This document records the system **as it actually is**. It is descriptive, never
aspirational: a plan for something not yet built belongs in an [ADR](../ADRs/) as a
*Proposed* decision, not here.

For *what the system must do*, see [requirements.md](../requirements/requirements.md); for
*why* these choices were made, see [ADR-001](../ADRs/ADR-001-java-spring-boot-maven.md),
[ADR-002](../ADRs/ADR-002-single-command-build-gate.md), and
[ADR-003](../ADRs/ADR-003-hexagonal-architecture-with-ddd.md).

> **Current state:** the hexagon is in place and enforced, but it is nearly empty. One
> walking-skeleton use case exists — schedule a shift, read it back — which satisfies **no
> stated functional requirement**. It is scaffolding that keeps the architecture rules
> honest, not a delivered capability. The `workforce` context is smaller still: an identity
> and a name, created ahead of its requirements so the cross-context rule is not vacuous
> (see [ADR-006](../ADRs/ADR-006-organise-packages-by-bounded-context.md)).

---

## Architecture overview

The repository holds `backend/` and, once a framework is chosen, `frontend/` as siblings
([ADR-007](../ADRs/ADR-007-monorepo-backend-and-frontend.md)). Only the backend exists today.

The backend is a single Spring Boot process, packaged as an executable JAR, serving HTTP on
port 8080 via embedded Tomcat. Its code is organised **by bounded context** — each context a
top-level package holding its own complete **hexagon**: a framework-free core surrounded by
adapters that translate between the outside world and the core's ports
([ADR-006](../ADRs/ADR-006-organise-packages-by-bounded-context.md)).

Two contexts exist: `scheduling`, which has the walking skeleton, and `workforce`, which has
only a domain. **Contexts may not depend on each other**, enforced by the build.

Dependencies point inward only. The domain knows nothing about the application layer, the
application layer knows nothing about adapters or Spring, and only `adapter` and
`configuration` import framework types.

```mermaid
flowchart LR
    client[HTTP client]

    subgraph scheduling [scheduling context]
        direction LR
        subgraph adapters [Adapters]
            web["adapter.in.web<br/>ShiftController"]
            store["adapter.out.persistence<br/>InMemoryShiftRepository"]
        end
        subgraph hexagon [Hexagon - framework-free]
            portIn["application.port.in<br/>ScheduleShiftUseCase, FindShiftUseCase"]
            service["application.service<br/>ShiftService"]
            portOut["application.port.out<br/>ShiftRepository"]
            domain["domain.model<br/>Shift, ShiftPeriod, ShiftId"]
        end
    end

    subgraph workforce [workforce context]
        wfDomain["domain.model<br/>Employee, EmployeeId"]
    end

    client --> web
    web --> portIn
    portIn --> service
    service --> domain
    service --> portOut
    store -.implements.-> portOut

    scheduling -. "may not depend on<br/>(enforced by the build)" .- workforce
```

## Components

| Component | Responsibility |
| --- | --- |
| `scheduling.domain.model` | `Shift` (entity, identity equality), `ShiftPeriod` and `ShiftId` (value objects), `InvalidShiftPeriodException`. Enforces the one invariant that exists: a period must end after it starts. JDK imports only. |
| `scheduling.application.port.in` | Driving ports — `ScheduleShiftUseCase`, `FindShiftUseCase` — plus the transport-independent `ScheduleShiftCommand`. |
| `scheduling.application.port.out` | Driven ports. `ShiftRepository` states what the application needs from storage; storage conforms to it, never the reverse. |
| `scheduling.application.service` | `ShiftService` orchestrates: turns a command into domain objects, lets the domain reject what is invalid, hands the result to a port. Holds no rules and no annotations. |
| `scheduling.adapter.in.web` | `ShiftController`, its request/response DTOs, and `ShiftExceptionHandler`, which translates a domain rule violation into an RFC 9457 problem with status 400. |
| `scheduling.adapter.out.persistence` | `InMemoryShiftRepository`, a `ConcurrentHashMap`. A placeholder until ADR-004. |
| `configuration` | `UseCaseConfiguration` constructs application services as beans, so they need no Spring annotation. |
| `workforce.domain.model` | `Employee` (entity, identity equality), `EmployeeId` (value object), `InvalidEmployeeNameException`. One invariant: a name that is not blank. No use cases and no adapters — see the current-state note above. |
| `HrSchedApplication` | Composition root. Starts Spring and nothing else. |
| Spring Boot Actuator | `/actuator/health`, `/health/liveness`, `/health/readiness`. Probes are enabled explicitly because Spring defaults them off outside Kubernetes. |

## Communication

Inbound HTTP only. Nothing calls out. Within the process, components talk by direct method
call through interfaces — there are no events, queues, or shared storage.

## Data flow

Scheduling a shift, end to end:

```mermaid
sequenceDiagram
    participant C as Client
    participant W as ShiftController
    participant U as ScheduleShiftUseCase
    participant S as ShiftService
    participant D as ShiftPeriod
    participant R as ShiftRepository
    participant A as InMemoryShiftRepository

    C->>W: POST /api/shifts {start, end}
    W->>U: ScheduleShiftCommand
    U->>S: schedule(command)
    S->>D: new ShiftPeriod(start, end)
    alt end is not after start
        D-->>C: InvalidShiftPeriodException → 400 problem detail
    else valid
        S->>R: save(shift)
        R->>A: put into map
        S-->>W: Shift
        W-->>C: 201 Created + Location + ShiftResponse
    end
```

Nothing survives a restart: the only store is in memory.

## External dependencies and integration points

**At runtime: none.** No database, broker, or third-party API.

At build time:

| Dependency | Used for | Justification |
| --- | --- | --- |
| `spring-boot-starter-webmvc` | HTTP boundary and embedded servlet container | The framework chosen in ADR-001. **Boot 4.x renamed this** from `spring-boot-starter-web`. |
| `spring-boot-starter-actuator` | Health and readiness endpoints | The project baseline requires every service to expose them. |
| `spring-boot-starter-webmvc-test`, `spring-boot-starter-actuator-test` | Test scope | Boot 4.x splits the former single `spring-boot-starter-test` into per-concern starters. |
| `archunit-junit5` | Enforcing the hexagon in the build | Nothing in the JDK, Maven, or Spring can fail a build when the domain imports a framework type. Without it, ADR-003 is a comment. Apache-2.0, actively maintained. |
| `spotless-maven-plugin` + `google-java-format` | Format check bound to `validate` | See ADR-002. |

## Structural decisions not visible from the file layout

- **`domain` and `application` are both framework-free.** Application services carry no
  `@Service`; `configuration.UseCaseConfiguration` constructs them. Only `adapter` and
  `configuration` may import Spring.
- **The dependency rule is enforced, not merely documented.** `HexagonalArchitectureTest`
  runs in `./mvnw verify` with five rules: the domain depends on nothing but the JDK, the
  application is free of the framework, dependencies point inward, contexts do not depend on
  each other, and a context's own layers are free of cycles.
- **Every one of those five has been observed failing** on a deliberately introduced violation,
  then reverted. Four fail in isolation; `layers_within_a_context_are_free_of_cycles` fires
  alongside the inward-dependency rules, because any layer cycle also points outward.
- **A sixth rule was removed as dead.** A context-level cycle check had no failure mode left:
  any cycle between two contexts requires a dependency between them, which
  `contexts_do_not_depend_on_each_other` already forbids outright. Cycles are now checked one
  level deeper, between a context's own layers, where they can still occur.
- **Bounded contexts are independent, and this is enforced.**
  `contexts_do_not_depend_on_each_other` fails the build when one context references another.
  The exemption is narrow on purpose: only classes actually annotated `@Configuration` may
  cross contexts, not everything that happens to live in the `configuration` package — a
  package-wide exemption would be a sanctioned back door for cross-context logic. Confirmed by
  referencing `workforce.EmployeeId` from `scheduling.Shift` and watching the build fail.
- **There is no `shared` package.** Nothing is shared by two contexts yet. It will be added the
  first time two genuinely need the same type, and that deserves its own ADR — a shared kernel
  is a coupling, not a convenience.
- **The build is the boundary for style and correctness.** Spotless runs in `validate`, so a
  formatting violation fails the build before tests execute. There is no separate lint step to
  forget.
- **`Shift` is an entity, not a record.** Equality is by `ShiftId` alone, because two shifts
  with identical periods are still different shifts. `ShiftId` and `ShiftPeriod` are records,
  because they *are* their values.
- **`ShiftPeriod` makes an illegal state unrepresentable.** An instance cannot exist unless it
  ends after it starts, so no caller has to check.
- **`Shift` is not yet an aggregate root.** It will guard headcount, qualifications, and
  assignments once Q2 and Q4 are answered. Modelling them now would encode invented policy.
- **`Employee` rejects a null name with a `NullPointerException`, not a domain exception.**
  Null is a programming defect and must surface as a 500; only a blank name is a domain rule
  and a 400. Every type in the codebase orders its checks the same way: null first, rule second.
- **Persistence is deliberately absent.** No JPA or JDBC starter is on the classpath, so the
  storage decision (ADR-004) has not been made by accident. Replacing
  `InMemoryShiftRepository` is the whole of the change when it is.
- **Time is modelled as `Instant`.** Rosters are usually read in a site's local time, so this
  is likely to change once Q3 and Q5 are answered.
- **Jackson 3.** Boot 4.1.1 ships `tools.jackson.databind`, not `com.fasterxml.jackson.databind`.
