# Architecture Decision Records

One file per decision, recording **why** a solution was chosen — the context that forced it,
what it costs, and what was turned down. For *what the system must do* see
[requirements.md](../requirements/requirements.md); for *how it is built* see
[architecture.md](../architecture/architecture.md).

## Index

| ADR | Title | Status | Date |
| --- | --- | --- | --- |
| [ADR-001](ADR-001-java-spring-boot-maven.md) | Build HrSched on Java 21, Spring Boot, and Maven | Accepted, amended | 2026-08-29 |
| [ADR-002](ADR-002-single-command-build-gate.md) | Make `./mvnw verify` the single build gate, formatted by Spotless | Accepted, amended by [ADR-047](ADR-047-audit-dependencies-in-ci-with-osv-scanner.md) | 2026-08-29 |
| [ADR-003](ADR-003-hexagonal-architecture-with-ddd.md) | Structure the application as a hexagon, enforced by ArchUnit | Accepted, amended by [ADR-006](ADR-006-organise-packages-by-bounded-context.md), [ADR-008](ADR-008-context-integration.md) | 2026-08-29 |
| [ADR-004](ADR-004-postgresql-behind-the-driven-ports.md) | Store every aggregate in PostgreSQL, behind the driven ports | Accepted, amended by [ADR-011](ADR-011-issue-our-own-access-tokens.md), [ADR-026](ADR-026-make-overlapping-published-rosters-unrepresentable.md), [ADR-037](ADR-037-keep-roster-history-as-a-record-in-time.md), [ADR-041](ADR-041-make-concurrent-changes-take-turns-on-the-row.md), [ADR-055](ADR-055-read-an-account-or-session-without-an-organisation-where-none-is-known.md) | 2026-09-06 |
| [ADR-005](ADR-005-roster-generation-behind-a-port.md) | Automatic roster generation, behind a port, with a greedy first implementation | Superseded by [ADR-044](ADR-044-generate-rosters-with-or-tools-cp-sat.md) | 2026-08-30 |
| [ADR-006](ADR-006-organise-packages-by-bounded-context.md) | Organise packages by bounded context, not by layer | Accepted, amended by [ADR-008](ADR-008-context-integration.md) | 2026-08-29 |
| [ADR-007](ADR-007-monorepo-backend-and-frontend.md) | Keep backend and frontend in one repository, as sibling directories | Accepted, amended by [ADR-013](ADR-013-react-typescript-vite-frontend.md), [ADR-014](ADR-014-root-task-runner.md), [ADR-047](ADR-047-audit-dependencies-in-ci-with-osv-scanner.md) | 2026-08-29 |
| [ADR-008](ADR-008-context-integration.md) | Contexts integrate through published ports and a minimal shared kernel | Accepted, amended | 2026-08-30 |
| [ADR-009](ADR-009-tenant-isolation-in-the-domain.md) | Carry tenancy in the domain and enforce it at the application boundary | Accepted, amended by [ADR-004](ADR-004-postgresql-behind-the-driven-ports.md), [ADR-011](ADR-011-issue-our-own-access-tokens.md), [ADR-030](ADR-030-confine-the-operator-to-the-register-of-organisations.md), [ADR-055](ADR-055-read-an-account-or-session-without-an-organisation-where-none-is-known.md) | 2026-08-30 |
| [ADR-010](ADR-010-score-a-roster-by-three-objectives-each-organisation-weights.md) | Score a roster by three objectives, weighted by each organisation | Accepted | 2026-10-20 |
| [ADR-011](ADR-011-issue-our-own-access-tokens.md) | Issue our own signed access tokens, and resolve the actor from the stored account and session on every request | Accepted | 2026-09-16 |
| [ADR-012](ADR-012-acting-identity-carries-its-employee-record.md) | The acting identity carries the employee record it is | Accepted, amended by [ADR-011](ADR-011-issue-our-own-access-tokens.md), [ADR-031](ADR-031-take-the-organisation-from-the-account.md) | 2026-08-30 |
| [ADR-013](ADR-013-react-typescript-vite-frontend.md) | Build the frontend on React, TypeScript and Vite, talking to the backend over a dev proxy | Accepted, amended | 2026-08-30 |
| [ADR-014](ADR-014-root-task-runner.md) | Orchestrate both builds from the root with a Node script | Accepted | 2026-08-30 |
| [ADR-015](ADR-015-report-coverage-without-a-threshold.md) | Report test coverage on both projects, and never enforce a threshold | Accepted, amended | 2026-08-30 |
| [ADR-016](ADR-016-structured-logs-and-a-propagated-trace-id.md) | Emit structured JSON logs everywhere, and propagate a W3C trace id | Accepted, amended by [ADR-011](ADR-011-issue-our-own-access-tokens.md) | 2026-08-30 |
| [ADR-017](ADR-017-expose-metrics-to-be-scraped.md) | Expose metrics for a scraper to pull, and ship no monitoring stack | Accepted, amended by [ADR-049](ADR-049-refuse-the-metrics-to-every-caller-until-a-scraper-exists.md) | 2026-08-30 |
| [ADR-018](ADR-018-audit-through-a-port-not-a-queryable-trail.md) | Record every change and refusal through a driven port, and build no queryable trail | Accepted, amended by [ADR-004](ADR-004-postgresql-behind-the-driven-ports.md), [ADR-052](ADR-052-record-only-after-every-write-notifications-included.md) | 2026-08-30 |
| [ADR-019](ADR-019-an-application-layer-for-the-access-context.md) | Give the access context an application layer, and make the organisation a real record | Accepted, amended by [ADR-004](ADR-004-postgresql-behind-the-driven-ports.md), [ADR-011](ADR-011-issue-our-own-access-tokens.md), [ADR-031](ADR-031-take-the-organisation-from-the-account.md), [ADR-055](ADR-055-read-an-account-or-session-without-an-organisation-where-none-is-known.md) | 2026-09-01 |
| [ADR-020](ADR-020-refuse-a-request-naming-an-unknown-organisation.md) | Refuse a request naming an organisation nobody onboarded, at the boundary | Superseded by [ADR-031](ADR-031-take-the-organisation-from-the-account.md) | 2026-09-01 |
| [ADR-021](ADR-021-notifications-as-a-bounded-context.md) | Make notifications a bounded context, and let it hold no words | Accepted, amended by [ADR-004](ADR-004-postgresql-behind-the-driven-ports.md), [ADR-011](ADR-011-issue-our-own-access-tokens.md) | 2026-09-01 |
| [ADR-022](ADR-022-approving-a-swap-publishes-a-replacement-roster.md) | Approve a swap by publishing a replacement roster, never by editing one | Accepted, amended by [ADR-023](ADR-023-the-transaction-boundary-is-the-use-case.md) | 2026-09-01 |
| [ADR-023](ADR-023-the-transaction-boundary-is-the-use-case.md) | Make the use case the transaction boundary, wrapped at the composition root | Accepted, amended by [ADR-045](ADR-045-generate-outside-the-use-cases-transaction.md), [ADR-054](ADR-054-run-a-use-case-read-only-when-its-name-starts-with-find.md) | 2026-09-06 |
| [ADR-024](ADR-024-answer-unbindable-requests-with-a-problem-detail.md) | Answer a request Spring cannot bind with a problem detail, from one fallback handler | Accepted, amended by [ADR-011](ADR-011-issue-our-own-access-tokens.md), [ADR-053](ADR-053-bound-a-request-body-and-accept-only-utf-8-json.md) | 2026-09-14 |
| [ADR-025](ADR-025-edit-a-draft-roster-with-controls-not-drag-and-drop.md) | Edit a draft roster through controls on a board, not by drag and drop | Accepted | 2026-09-14 |
| [ADR-026](ADR-026-make-overlapping-published-rosters-unrepresentable.md) | Make two overlapping published rosters of one organisation unrepresentable in the schema | Superseded by [ADR-037](ADR-037-keep-roster-history-as-a-record-in-time.md) | 2026-09-14 |
| [ADR-027](ADR-027-take-turns-publishing-within-an-organisation.md) | Make publications within one organisation take turns, with a transaction-scoped advisory lock | Accepted, amended by [ADR-037](ADR-037-keep-roster-history-as-a-record-in-time.md), [ADR-039](ADR-039-lapse-pending-swaps-in-the-publication-that-supersedes-their-roster.md) | 2026-09-14 |
| [ADR-028](ADR-028-keep-the-session-in-memory-and-a-rotating-refresh-cookie.md) | Keep the browser session as an in-memory access token and a rotating refresh cookie | Accepted, amended by [ADR-048](ADR-048-keep-a-browsers-tabs-on-one-session.md) | 2026-09-16 |
| [ADR-029](ADR-029-provision-accounts-from-the-top-down.md) | Provision accounts from the top down, with temporary passwords, and bootstrap the operator from configuration | Accepted, amended by [ADR-055](ADR-055-read-an-account-or-session-without-an-organisation-where-none-is-known.md) | 2026-09-16 |
| [ADR-030](ADR-030-confine-the-operator-to-the-register-of-organisations.md) | Confine the platform operator to the register of organisations | Accepted, amended | 2026-09-16 |
| [ADR-031](ADR-031-take-the-organisation-from-the-account.md) | Take the organisation from the account, not from the request | Accepted | 2026-09-21 |
| [ADR-032](ADR-032-close-the-security-chain-by-default.md) | Close the security chain by default, and name every route that is open | Accepted, amended by [ADR-049](ADR-049-refuse-the-metrics-to-every-caller-until-a-scraper-exists.md) | 2026-09-21 |
| [ADR-033](ADR-033-judge-r3-across-authoritative-rosters-under-the-publication-lock.md) | Judge R3 across authoritative rosters in the application, under the publication lock | Accepted, amended by [ADR-042](ADR-042-judge-a-roster-by-one-check-publication-and-a-read-share.md) | 2026-10-10 |
| [ADR-034](ADR-034-count-working-days-in-each-shifts-own-time-zone.md) | Count working days in each shift's own time zone | Accepted | 2026-10-10 |
| [ADR-037](ADR-037-keep-roster-history-as-a-record-in-time.md) | Keep roster history as a record of when each roster governed, written once | Accepted, amended | 2026-10-14 |
| [ADR-038](ADR-038-read-a-constraint-version-back-by-its-number.md) | Read a constraint version back by its number, and answer version one from the domain | Accepted | 2026-10-15 |
| [ADR-039](ADR-039-lapse-pending-swaps-in-the-publication-that-supersedes-their-roster.md) | Lapse pending swaps in the publication that supersedes their roster | Accepted, amended by [ADR-052](ADR-052-record-only-after-every-write-notifications-included.md) | 2026-10-15 |
| [ADR-040](ADR-040-withdraw-a-declaration-by-what-it-says.md) | Withdraw a declaration by what it says, and keep nothing of it | Accepted | 2026-10-18 |
| [ADR-041](ADR-041-make-concurrent-changes-take-turns-on-the-row.md) | Make concurrent changes to one aggregate take turns on its row, rather than reject the later | Accepted | 2026-10-19 |
| [ADR-042](ADR-042-judge-a-roster-by-one-check-publication-and-a-read-share.md) | Judge a roster by one check that publication and an advisory read share | Accepted | 2026-10-19 |
| [ADR-043](ADR-043-defer-typescript-7-until-typescript-eslint-supports-it.md) | Stay on TypeScript 6 until typescript-eslint supports TypeScript 7 | Accepted | 2026-10-20 |
| [ADR-044](ADR-044-generate-rosters-with-or-tools-cp-sat.md) | Generate rosters with CP-SAT from Google OR-Tools | Accepted | 2026-10-22 |
| [ADR-045](ADR-045-generate-outside-the-use-cases-transaction.md) | Generate outside the use case's transaction, between a read and a write | Accepted | 2026-10-22 |
| [ADR-046](ADR-046-generate-in-the-background-and-keep-the-job-in-postgresql.md) | Generate in the background, and keep each generation in PostgreSQL | Accepted, amended | 2026-10-23 |
| [ADR-047](ADR-047-audit-dependencies-in-ci-with-osv-scanner.md) | Audit dependencies for known vulnerabilities in CI with OSV-Scanner | Accepted | 2026-10-27 |
| [ADR-048](ADR-048-keep-a-browsers-tabs-on-one-session.md) | Keep one browser's tabs on one session, over a channel that carries two words | Accepted | 2026-10-27 |
| [ADR-049](ADR-049-refuse-the-metrics-to-every-caller-until-a-scraper-exists.md) | Refuse the metrics endpoints to every caller until a scraper exists | Accepted | 2026-10-27 |
| [ADR-050](ADR-050-reject-the-protected-resource-metadata-endpoint.md) | Reject the OAuth 2.0 protected-resource metadata endpoint | Accepted | 2026-10-27 |
| [ADR-051](ADR-051-record-why-each-frontend-dependency-is-there.md) | Record why each frontend dependency is there | Accepted | 2026-10-29 |
| [ADR-052](ADR-052-record-only-after-every-write-notifications-included.md) | Record an audit line only after every write, a notification included | Accepted, amended | 2026-10-30 |
| [ADR-053](ADR-053-bound-a-request-body-and-accept-only-utf-8-json.md) | Bound a request body in bytes, and accept nothing but UTF-8 JSON | Accepted, amended | 2026-10-30 |
| [ADR-054](ADR-054-run-a-use-case-read-only-when-its-name-starts-with-find.md) | Run a use case read-only when its name starts with `find` | Accepted, amended | 2026-10-30 |
| [ADR-055](ADR-055-read-an-account-or-session-without-an-organisation-where-none-is-known.md) | Read an account or a session without an organisation where none is known | Accepted | 2026-10-30 |
| [ADR-056](ADR-056-refuse-a-fraction-where-a-whole-number-is-bound.md) | Refuse a fraction where a whole number is bound | Accepted, amended | 2026-10-30 |

## Decisions waiting to be recorded

Known-open and blocking work elsewhere. An entry here is a placeholder, **not** a decision.

Only ADR-035 and ADR-036 are reserved numbers. A row marked "Not yet numbered" holds none: when
its record is written, it takes the highest number in either table on this page, plus one, and
its row moves to the index.

| Will be | Decision | Blocks |
| --- | --- | --- |
| ADR-035 | The AWS topology: ECS Fargate, RDS, and one CloudFront origin ([#153](https://github.com/NaimElijah/HrSched/issues/153)) | Running on AWS (staging) |
| ADR-036 | A CDK app as the third buildable project ([#154](https://github.com/NaimElijah/HrSched/issues/154)) | Running on AWS (staging) |
| Not yet numbered | The Operator's exception to ADR-030: resetting an organisation's Admin, with what the out-of-band check must record; amends ADR-030 ([#114](https://github.com/NaimElijah/HrSched/issues/114), Q28) | Building the reset ([#107](https://github.com/NaimElijah/HrSched/issues/107)) |
| Not yet numbered | The second factor for Operators and Admins ([#109](https://github.com/NaimElijah/HrSched/issues/109), Q31) | Building it (#109) |
| Not yet numbered | An `audit` bounded context for a durable, queryable trail; extends ADR-018 ([#31](https://github.com/NaimElijah/HrSched/issues/31), Q20) | Building the trail (#31) |
| Not yet numbered | The two-way swap exchange; extends or supersedes ADR-022 ([#82](https://github.com/NaimElijah/HrSched/issues/82), Q22) | Building the exchange (#82) |
| Not yet numbered | The platform operator's lifecycle, how many there may be and how one is rotated; extends or supersedes ADR-029 ([#75](https://github.com/NaimElijah/HrSched/issues/75), Q30) | Operator administration (#75) |
| Not yet numbered | A channel that informs a colleague beyond the in-app inbox, email or push (Q21, ADR-021) | Anything beyond the inbox |
| Not yet numbered | Whether and how the metrics endpoints reopen to a scraper; amends ADR-049 ([#78](https://github.com/NaimElijah/HrSched/issues/78), Q19) | Scraping the metrics ([#165](https://github.com/NaimElijah/HrSched/issues/165)) |
| Not yet numbered | Which dependency licences HrSched accepts, and what it owes for what it ships ([#280](https://github.com/NaimElijah/HrSched/issues/280)) | Handing a jar or an image to a customer |
| Not yet numbered | Where models are trained and served, and how the hexagon reaches them ([#189](https://github.com/NaimElijah/HrSched/issues/189)) | The first learned model (epic [#203](https://github.com/NaimElijah/HrSched/issues/203)) |
| Not yet numbered | The design toolchain for AI-assisted UI work ([#174](https://github.com/NaimElijah/HrSched/issues/174)) | Designing the UI with an assistant |
| Not yet numbered | The GitHub plan a blocking merge gate and a production approval need ([#148](https://github.com/NaimElijah/HrSched/issues/148)) | A red pipeline that stops a merge |
| Not yet numbered | Whether to adopt multi-module Maven, Spring Modulith and `@Service` on application services, or to reject each again with a new trigger; their revisit triggers have fired ([#58](https://github.com/NaimElijah/HrSched/issues/58)) | Nothing: the triggers fired and no outcome is recorded |

## Conventions

- **Filename** — `ADR-NNN-short-kebab-title.md`, zero-padded and monotonically increasing.
  Numbers are never reused, even if an ADR is superseded.
- **Contents** — Title, Status, Date, Context, Decision, Consequences, Alternatives
  considered. Start from [`adr-template.md`](adr-template.md).
- **Status** — `Proposed`, `Accepted`, or `Superseded by ADR-NNN`. A record is superseded only
  when the whole of it is replaced.
- **Amended** — when later work overtakes only a part of an accepted decision, a consequence it
  stated, a count or a version, or extends it without replacing any of it, the decision still
  stands and the status stays `Accepted`. An extension counts as an amendment: a reader of the
  earlier record finds what was added to it. A dated note goes beneath the status line, as a
  bullet: `Amended YYYY-MM-DD: <what changed>`, naming the later record where there is one. The
  body is not rewritten. A note gives no count or version that will move again; it says where the
  current one lives. In the index, the Status column reads `Accepted, amended by ADR-NNN`, naming
  each later record, or `Accepted, amended` where the change has no record of its own.
- **Erratum** — the same bullet for a reference that was wrong when written, such as a wrong
  question number: `Erratum YYYY-MM-DD: <the wrong reference> should read <the right one>`. It
  changes no decision, so the index does not mark it.
- **Append-only** — an accepted ADR is never rewritten or deleted; its status line is the one
  place that changes, by an amendment note, an erratum or `Superseded by ADR-NNN`. A note, once
  written, is never edited: a later change adds another note. To change course, write a new ADR
  and mark the old one `Superseded by ADR-NNN`. The history is the point.
- **Numbering** — a new ADR takes the highest number in either table on this page, plus one. The
  reserved numbers in *Decisions waiting to be recorded* are the only gaps.
- **Same commit** — when work invalidates or extends a decision, the status change ships in
  the commit that changes the behaviour, not afterwards.

## When to write one

Whenever a decision shapes the architecture: language, framework, library, tool or technology
choices; layering and module boundaries; data storage and schema strategy; API style and
contracts; authentication; deployment and infrastructure topology; build and test strategy;
or any deliberate trade-off a future maintainer would otherwise have to reverse-engineer.

Write them for **rejections** too — something considered and turned down — including the
concrete threshold or event that would make us revisit it.
