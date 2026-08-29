# Architecture Decision Records

One file per decision, recording **why** a solution was chosen — the context that forced it,
what it costs, and what was turned down. For *what the system must do* see
[requirements.md](../requirements/requirements.md); for *how it is built* see
[architecture.md](../architecture/architecture.md).

## Index

| ADR | Title | Status | Date |
| --- | --- | --- | --- |
| [ADR-001](ADR-001-java-spring-boot-maven.md) | Build HrSched on Java 21, Spring Boot, and Maven | Accepted | 2026-08-29 |
| [ADR-002](ADR-002-single-command-build-gate.md) | Make `./mvnw verify` the single build gate, formatted by Spotless | Accepted | 2026-08-29 |
| [ADR-003](ADR-003-hexagonal-architecture-with-ddd.md) | Structure the application as a hexagon, enforced by ArchUnit | Accepted | 2026-08-29 |
| [ADR-004](ADR-004-organise-packages-by-bounded-context.md) | Organise packages by bounded context, not by layer | Accepted | 2026-08-29 |

## Decisions waiting to be recorded

Known-open and blocking work elsewhere. An entry here is a placeholder, **not** a decision.

| Will be | Decision | Blocks |
| --- | --- | --- |
| ADR-006 | Data storage and schema strategy | Replacing the in-memory adapter; persistence guarantees; migration rules |
| ADR-007 | Roster generation: manual, assisted, or solver-driven | Whether a constraint solver becomes a dependency |

## Conventions

- **Filename** — `ADR-NNN-short-kebab-title.md`, zero-padded and monotonically increasing.
  Numbers are never reused, even if an ADR is superseded.
- **Contents** — Title, Status, Date, Context, Decision, Consequences, Alternatives
  considered. Start from [`adr-template.md`](adr-template.md).
- **Status** — `Proposed`, `Accepted`, or `Superseded by ADR-NNN`.
- **Append-only** — an accepted ADR is never rewritten or deleted. To change course, write a
  new ADR and mark the old one `Superseded by ADR-NNN`. The history is the point.
- **Same commit** — when work invalidates or extends a decision, the status change ships in
  the commit that changes the behaviour, not afterwards.

## When to write one

Whenever a decision shapes the architecture: language, framework, library, tool or technology
choices; layering and module boundaries; data storage and schema strategy; API style and
contracts; authentication; deployment and infrastructure topology; build and test strategy;
or any deliberate trade-off a future maintainer would otherwise have to reverse-engineer.

Write them for **rejections** too — something considered and turned down — including the
concrete threshold or event that would make us revisit it.
