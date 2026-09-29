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
| [ADR-004](ADR-004-postgresql-behind-the-driven-ports.md) | Store every aggregate in PostgreSQL, behind the driven ports | Accepted | 2026-09-06 |
| [ADR-005](ADR-005-roster-generation-behind-a-port.md) | Automatic roster generation, behind a port, with a greedy first implementation | Accepted | 2026-08-30 |
| [ADR-006](ADR-006-organise-packages-by-bounded-context.md) | Organise packages by bounded context, not by layer | Accepted | 2026-08-29 |
| [ADR-007](ADR-007-monorepo-backend-and-frontend.md) | Keep backend and frontend in one repository, as sibling directories | Accepted | 2026-08-29 |
| [ADR-008](ADR-008-context-integration.md) | Contexts integrate through published ports and a minimal shared kernel | Accepted | 2026-08-30 |
| [ADR-009](ADR-009-tenant-isolation-in-the-domain.md) | Carry tenancy in the domain and enforce it at the application boundary | Accepted | 2026-08-30 |
| [ADR-011](ADR-011-issue-our-own-access-tokens.md) | Issue our own signed access tokens, and resolve the actor from the stored account and session on every request | Accepted | 2026-09-16 |
| [ADR-012](ADR-012-acting-identity-carries-its-employee-record.md) | The acting identity carries the employee record it is | Accepted | 2026-08-30 |
| [ADR-013](ADR-013-react-typescript-vite-frontend.md) | Build the frontend on React, TypeScript and Vite, talking to the backend over a dev proxy | Accepted | 2026-08-30 |
| [ADR-014](ADR-014-root-task-runner.md) | Orchestrate both builds from the root with a Node script | Accepted | 2026-08-30 |
| [ADR-015](ADR-015-report-coverage-without-a-threshold.md) | Report test coverage on both projects, and never enforce a threshold | Accepted | 2026-08-30 |
| [ADR-016](ADR-016-structured-logs-and-a-propagated-trace-id.md) | Emit structured JSON logs everywhere, and propagate a W3C trace id | Accepted | 2026-08-30 |
| [ADR-017](ADR-017-expose-metrics-to-be-scraped.md) | Expose metrics for a scraper to pull, and ship no monitoring stack | Accepted | 2026-08-30 |
| [ADR-018](ADR-018-audit-through-a-port-not-a-queryable-trail.md) | Record every change and refusal through a driven port, and build no queryable trail | Accepted | 2026-08-30 |
| [ADR-019](ADR-019-an-application-layer-for-the-access-context.md) | Give the access context an application layer, and make the organisation a real record | Accepted | 2026-09-01 |
| [ADR-020](ADR-020-refuse-a-request-naming-an-unknown-organisation.md) | Refuse a request naming an organisation nobody onboarded, at the boundary | Superseded by [ADR-031](ADR-031-take-the-organisation-from-the-account.md) | 2026-09-01 |
| [ADR-021](ADR-021-notifications-as-a-bounded-context.md) | Make notifications a bounded context, and let it hold no words | Accepted | 2026-09-01 |
| [ADR-022](ADR-022-approving-a-swap-publishes-a-replacement-roster.md) | Approve a swap by publishing a replacement roster, never by editing one | Accepted | 2026-09-01 |
| [ADR-023](ADR-023-the-transaction-boundary-is-the-use-case.md) | Make the use case the transaction boundary, wrapped at the composition root | Accepted | 2026-09-06 |
| [ADR-024](ADR-024-answer-unbindable-requests-with-a-problem-detail.md) | Answer a request Spring cannot bind with a problem detail, from one fallback handler | Accepted | 2026-09-14 |
| [ADR-025](ADR-025-edit-a-draft-roster-with-controls-not-drag-and-drop.md) | Edit a draft roster through controls on a board, not by drag and drop | Accepted | 2026-09-14 |
| [ADR-026](ADR-026-make-overlapping-published-rosters-unrepresentable.md) | Make two overlapping published rosters of one organisation unrepresentable in the schema | Accepted | 2026-09-14 |
| [ADR-027](ADR-027-take-turns-publishing-within-an-organisation.md) | Make publications within one organisation take turns, with a transaction-scoped advisory lock | Accepted | 2026-09-14 |
| [ADR-028](ADR-028-keep-the-session-in-memory-and-a-rotating-refresh-cookie.md) | Keep the browser session as an in-memory access token and a rotating refresh cookie | Accepted | 2026-09-16 |
| [ADR-029](ADR-029-provision-accounts-from-the-top-down.md) | Provision accounts from the top down, with temporary passwords, and bootstrap the operator from configuration | Accepted | 2026-09-16 |
| [ADR-030](ADR-030-confine-the-operator-to-the-register-of-organisations.md) | Confine the platform operator to the register of organisations | Accepted | 2026-09-16 |
| [ADR-031](ADR-031-take-the-organisation-from-the-account.md) | Take the organisation from the account, not from the request | Accepted | 2026-09-21 |
| [ADR-032](ADR-032-close-the-security-chain-by-default.md) | Close the security chain by default, and name every route that is open | Accepted | 2026-09-21 |

## Decisions waiting to be recorded

Known-open and blocking work elsewhere. An entry here is a placeholder, **not** a decision.

| Will be | Decision | Blocks |
| --- | --- | --- |
| ADR-010 | Objectives: what makes one legal roster better than another | Introducing a real solver (ADR-005) |

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
