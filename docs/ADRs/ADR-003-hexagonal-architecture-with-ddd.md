# ADR-003: Structure the application as a hexagon, enforced by ArchUnit

- **Status:** Accepted
  - **Amended 2026-10-30:** The count of "Four rules" has moved.
    [ADR-006](ADR-006-organise-packages-by-bounded-context.md),
    [ADR-008](ADR-008-context-integration.md) and later records added more, and
    `HexagonalArchitectureTest` holds the current set. The dependency rule the four state still
    holds.
- **Date:** 2026-08-29

## Context

HrSched's value is in constraint checking: rest periods, qualifications, contracted hours,
and working-time regulation. That logic is worth protecting from the things that change for
unrelated reasons — HTTP, persistence, and the framework itself. A rostering rule should be
readable and testable without booting Spring or starting a database.

The forces at the time of this decision:

- **The domain rules are not yet known.** Q2 (roles), Q3 (multi-tenancy), and Q4
  (jurisdiction) in `docs/requirements/requirements.md` are open, so the *tactical* DDD
  patterns — aggregates, their invariants, bounded contexts — cannot honestly be modelled.
  The *structural* decision does not depend on them, and is cheapest to make while there is
  almost no code to move.
- **Conventions in documents rot.** A dependency rule that only a reviewer enforces is
  violated by accident within weeks, and each violation is cheaper to allow than to fix.
- **One developer, one bounded context, no deployment target.** Whatever is chosen has to
  stay proportionate to that.
- **Architecture rules over empty packages pass vacuously.** A green test that proves
  nothing is worse than no test, because it manufactures confidence.

## Decision

We structure the application as a **hexagon (ports and adapters)** inside the existing
single Maven module, with the dependency direction visible in the package path:

```
domain/model          entities and value objects — JDK only
application/port/in   driving ports (use case interfaces)
application/port/out  driven ports (repository and gateway interfaces)
application/service   use case implementations
adapter/in/web        REST controllers and their DTOs
adapter/out/persistence  driven port implementations
configuration         Spring wiring
```

**`domain` and `application` are both framework-free.** Application services carry no
`@Service` annotation; they are constructed explicitly in `configuration`. Only `adapter`
and `configuration` may see Spring.

**Enforcement is an ArchUnit test** (`HexagonalArchitectureTest`) bound into `./mvnw verify`.
Four rules: the domain depends on nothing but the JDK, the application is free of the
framework, dependencies point inward, and packages are free of cycles.

**Tactical DDD is started, not finished.** `ShiftId` and `ShiftPeriod` are value objects,
`Shift` is an entity with identity equality, and `ShiftPeriod` makes an illegal state
unrepresentable. That is the extent of what the open requirements support. `Shift` is *not*
yet an aggregate root, because the invariants it would guard are unanswered questions.

**A thin walking skeleton** — schedule a shift, read it back — exists so the ArchUnit rules
have real classes to check. It satisfies no stated functional requirement and is not a
feature; it is scaffolding for the architecture, and is labelled as such wherever it appears.

## Consequences

**Easier**

- Domain and use case tests run with `new`, no container, in milliseconds — `ShiftServiceTest`
  uses a hand-written stub of its own port and needs neither Spring nor a mocking framework.
- The storage decision (ADR-004) can be made later and applied by replacing one adapter
  behind `ShiftRepository`, with nothing inward of the port changing.
- A violation of the dependency rule fails the build, for everyone, including CI. Both rules
  were verified to fail by deliberately introducing violations before this was accepted.

**Harder**

- **More classes for the same behaviour.** Scheduling a shift touches a request DTO, a
  command, a port, a service, a domain object, a repository port, an adapter, and a response
  DTO. For genuinely trivial CRUD this is pure overhead, and it will feel like it.
- **Every use case must be wired by hand** in `UseCaseConfiguration`. Forgetting to is a
  runtime failure at startup, not a compile error.
- **Mapping is duplicated** at each boundary — `ScheduleShiftRequest` and
  `ScheduleShiftCommand` carry the same two fields today. That redundancy is the price of
  letting the wire format and the use case change independently.
- **Enforcement is a test, not the compiler.** Someone can delete the ArchUnit test and the
  build stays green. A multi-module layout would make violations physically impossible.
- ArchUnit adds roughly two seconds to every build.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Multi-module Maven** (domain / application / adapter / bootstrap) | The compiler would enforce the boundary — the domain module could not see Spring because Spring would not be on its classpath. Rejected as disproportionate: four poms and a reactor build for one developer, one bounded context, and a handful of classes. **Revisit when** a second deployable or a second bounded context appears, or the team passes roughly three people. |
| **Layered `domain` / `application` / `infrastructure` naming** | Fewer directories and more familiar, but `infrastructure` hides whether a class drives the application or is driven by it — exactly the distinction this project needs to keep visible. |
| **`@Service` on application services** | Pragmatic and common, but it puts a framework annotation inside the hexagon and makes "the application is framework-free" unenforceable. **Revisit if** hand-wiring in `configuration` becomes a genuine maintenance burden — say, past roughly twenty use cases. |
| **Convention documented, not enforced** | The cheapest option and the one that rots first. Rejected outright: the reason to choose an architecture is to keep it. |
| **Modelling the real domain now** | Would require inventing answers to Q2, Q3, and Q4 and encoding guessed policy in the one place that must be trustworthy. Deferred until those questions are answered. |
| **JPA entities as the domain model** | Would make ADR-004 by default and couple the domain to a persistence framework — the specific outcome this ADR exists to prevent. |
