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
| [ADR-005](ADR-005-roster-generation-behind-a-port.md) | Automatic roster generation, behind a port, with a greedy first implementation | Accepted | 2026-08-30 |
| [ADR-006](ADR-006-organise-packages-by-bounded-context.md) | Organise packages by bounded context, not by layer | Accepted | 2026-08-29 |
| [ADR-007](ADR-007-monorepo-backend-and-frontend.md) | Keep backend and frontend in one repository, as sibling directories | Accepted | 2026-08-29 |
| [ADR-008](ADR-008-context-integration.md) | Contexts integrate through published ports and a minimal shared kernel | Accepted | 2026-08-30 |
| [ADR-009](ADR-009-tenant-isolation-in-the-domain.md) | Carry tenancy in the domain and enforce it at the application boundary | Accepted | 2026-08-30 |
| [ADR-012](ADR-012-acting-identity-carries-its-employee-record.md) | The acting identity carries the employee record it is | Accepted | 2026-08-30 |
| [ADR-013](ADR-013-react-typescript-vite-frontend.md) | Build the frontend on React, TypeScript and Vite, talking to the backend over a dev proxy | Accepted | 2026-08-30 |
| [ADR-014](ADR-014-root-task-runner.md) | Orchestrate both builds from the root with a Node script | Accepted | 2026-08-30 |
| [ADR-015](ADR-015-report-coverage-without-a-threshold.md) | Report test coverage on both projects, and never enforce a threshold | Accepted | 2026-08-30 |
| [ADR-016](ADR-016-structured-logs-and-a-propagated-trace-id.md) | Emit structured JSON logs everywhere, and propagate a W3C trace id | Accepted | 2026-08-30 |
| [ADR-017](ADR-017-expose-metrics-to-be-scraped.md) | Expose metrics for a scraper to pull, and ship no monitoring stack | Accepted | 2026-08-30 |
| [ADR-018](ADR-018-audit-through-a-port-not-a-queryable-trail.md) | Record every change and refusal through a driven port, and build no queryable trail | Accepted | 2026-08-30 |
| [ADR-019](ADR-019-an-application-layer-for-the-access-context.md) | Give the access context an application layer, and make the organisation a real record | Accepted | 2026-09-01 |
| [ADR-020](ADR-020-refuse-a-request-naming-an-unknown-organisation.md) | Refuse a request naming an organisation nobody onboarded, at the boundary | Accepted | 2026-09-01 |
| [ADR-021](ADR-021-notifications-as-a-bounded-context.md) | Make notifications a bounded context, and let it hold no words | Accepted | 2026-09-01 |
| [ADR-022](ADR-022-approving-a-swap-publishes-a-replacement-roster.md) | Approve a swap by publishing a replacement roster, never by editing one | Accepted | 2026-09-01 |

## Decisions waiting to be recorded

Known-open and blocking work elsewhere. An entry here is a placeholder, **not** a decision.

| Will be | Decision | Blocks |
| --- | --- | --- |
| ADR-004 | Data storage and schema strategy | Replacing the in-memory adapters; persistence guarantees; migration rules; whether tenant isolation gains a database-level guarantee |
| ADR-010 | Objectives: what makes one legal roster better than another | Introducing a real solver (ADR-005) |
| ADR-011 | Authentication and the identity provider | Deleting the header-based actor placeholder |

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
