<!--
Maintainers: this file loads the first time a file under backend/ is read or edited, on top of
.claude/CLAUDE.md, which every session already has. It holds what only backend work needs. Keep
it under 200 lines. A rule that also binds the frontend, or that a session must know before it
opens any file (commands, the highest-stakes invariants), belongs in .claude/CLAUDE.md instead.
This comment is stripped before Claude sees the file.
-->

# backend/CLAUDE.md

## Layout

Inside `backend/src/main/java/io/github/naimelijah/hrsched/`, the package path *is* the
dependency rule — see ADR-003 and ADR-006. Every context repeats the same shape:

- `shared/domain/model/` — the kernel: identities (`OrganisationId`, `EmployeeId`,
  `QualificationId`, `UserId`), `TimeRange`, `Role`, and the sealed `Actor` — a `TenantActor` or
  the `Operator` — with the exceptions its access checks throw. Depends on no context, and must
  stay that way. Add no behaviour beyond the actor's access checks already there.
- `<context>/domain/model/` — entities and value objects. **JDK imports only.**
- `<context>/application/port/in/` — driving ports (use case interfaces) and commands.
- `<context>/application/port/out/` — driven ports (what the application needs outside).
- `<context>/application/service/` — use case implementations. **No Spring annotations.**
- `<context>/adapter/in/<kind>/` — what drives the context: `web/` (controllers, DTOs, exception
  translation) everywhere, and `bootstrap/` and `token/` in access. May use Spring.
- `<context>/adapter/out/<kind>/` — driven port implementations, one package per thing driven:
  `persistence/` and `audit/` everywhere, `generation/`, `password/` and `token/` where a context
  needs one, and a package named after another context it consumes. May use Spring. Each
  `Jdbc*Repository` owns its own SQL; there is deliberately no shared helper package, because
  `ContextPackages` would treat a new top-level package as a bounded context and every context
  touching it would fail the cross-context rule.

Contexts today: `scheduling` (shifts, rosters, constraints, generation), `workforce` (employees,
qualifications, availability), `access` (organisations, accounts, sessions and tokens),
`notifications` (what somebody has been told). At the root beside them sit
`configuration/` and `HrSchedApplication`.

A context may reference another context's `application.port.in` package and **nothing else**
(ADR-008). Consuming one belongs in an `adapter/out/<that context>/` class, and there are three:
`scheduling/adapter/out/workforce/WorkforceDirectoryAdapter`,
`scheduling/adapter/out/notification/NotificationAdapter` and
`access/adapter/out/workforce/WorkforceEmployeeDirectory`.

Also inside `backend/`: `src/main/resources/db/migration/` holds the Flyway migrations — the only
way the schema changes — and `compose.yaml` holds the local database, beside the backend rather
than at the root because Boot looks in the working directory.

## Architecture tests

- **Dependencies point inward, and contexts are independent. Both are enforced.**
  `HexagonalArchitectureTest` fails the build if the domain imports Spring, if the application
  touches an adapter, if a context reaches past another context's published ports, if the kernel
  depends on a context, or if a context's own layers form a cycle. Do not weaken or delete a rule
  to make code pass — the rule is the point. Deleting one because it is provably unable to fail
  is a different thing, and needs the proof.
- **When adding an architecture rule, watch it fail first.** All ten rules here have been
  observed failing on a deliberately introduced violation, then reverted. A rule that has only
  ever been green proves nothing — one rule was deleted after it turned out it could no longer
  fail, being fully subsumed by what is now
  `contexts_touch_only_published_ports_of_other_contexts`. The same applies to a test: a fix
  whose test was never seen failing has not been shown to fix anything.

## Adding a use case

Pick the context → domain concept → port in/out → service (no annotation) → register in
`UseCaseConfiguration` → adapters → decide who may call the route in `SecurityConfiguration` →
tests. Two of those steps fail late rather than at compile time. A forgotten bean registration
fails at startup. A new controller left out of its context's
`@RestControllerAdvice(assignableTypes = …)` handler fails silently: its domain exceptions are
never translated.

## Acting identity and tenancy

- **Who is acting is an argument, never an ambient read.** `SecurityContextHolder` is forbidden
  outside `configuration.security`, where one Spring callback has no parameter to take the caller
  from. A use case takes an `Actor`; a controller receives one through `@AuthenticationPrincipal`,
  and takes the organisation from that actor through its `actingIn` helper (`organisationOf` in
  `AccountController`). `GET /api/organisations/{id}` names in its path which organisation to
  read, not who is acting, and its use case answers a tenant naming another with a 404. `X-User`,
  `X-Role`, `X-Organisation` and `X-Employee` are read by nothing; do not reintroduce them.
- **Tenancy is in every signature.** Repository lookups take an `OrganisationId`; there is no
  `findById(EmployeeId)`. Use cases take an `Actor` and call `requireAccessTo` first. A
  cross-tenant attempt is a 404, never a 403 — a 403 confirms the resource exists.
- **`UserId` and `EmployeeId` are never compared.** They are different identities on purpose. To
  ask whether an actor is the person being acted on, use `TenantActor.isThemself` — the link is
  carried, not inferred from matching UUIDs (ADR-012).
- **Only a Scheduler builds rosters.** `MAY_SCHEDULE` is `{SCHEDULER}`; an Admin manages people,
  qualifications and the constraint set. The role javadoc and the requirements table agree, and
  the permission sets must follow them rather than the other way round.

## The security chain

`SecurityConfiguration` ends in `denyAll`, so a route no rule matches is refused. Tenant routes
are matched by family — `/api/rosters/**`, `/api/swaps/**` and the rest of `TENANT_ROUTES` — so a
new endpoint under an existing prefix is **already open** to every tenant role, and only a new
prefix is unreachable until it is named. Either way, checking who may call it is the decision, not
a formality. The URL rules are the coarse outer line; the use case behind each route is what
decides.

## Persistence and transactions

Persistence is settled (ADR-004): PostgreSQL, hand-written SQL through `JdbcClient`, Flyway
migrations, one schema with `organisation_id` on every aggregate root (child tables are reached
only through their root). The domain may not carry `jakarta.persistence` annotations — the
architecture test fails on them — so JPA would need a parallel annotated model and would buy only
behaviour a hexagonal adapter must not leak.

**Every use case is one transaction**, wrapped in `UseCaseConfiguration` rather than annotated,
because the architecture test forbids Spring in the application layer (ADR-023). A new use case gets
that for free by being registered there — which is already mandatory.

## Audit and instrumentation

- **Instrumentation lives in adapters, and three rules enforce it.** Nothing prints anywhere; the
  domain may not touch `org.slf4j`, `ch.qos.logback` or `java.util.logging`; and neither the domain
  nor the application layer may touch `io.micrometer` or `io.opentelemetry`. So Micrometer's
  `@Timed` and `@Counted` cannot go on a use case, and a service that needs to record something
  records it through a driven port. See ADR-016 to ADR-018.
- **Personal data is unrepresentable in a log, not merely discouraged.** `AuditedChange` carries a
  bare `UUID` subject so a name cannot be written, and each context's `*AuditLogTest` asserts that
  nothing it writes is personal data. Attach fields with slf4j
  `addKeyValue`, never by interpolating them into the message: the message is a constant, and
  everything variable is a field the ECS encoder publishes as JSON.
- **The audit port is per context on purpose.** Each of the four contexts owns an `AuditTrail`,
  an `AuditedChange` with its own change enum, and a `*AuditLog` adapter. Access's change carries
  an `Optional<OrganisationId>`, because an operator's act belongs to no organisation. They are
  different types, not duplication, and anything shared would have to enter the kernel ADR-008
  keeps small. The collision shows up only in `UseCaseConfiguration`, which writes three of them
  out in full.
- **Recording happens after the save, and a refused act records nothing.** An entry for something
  that then failed to store is a record of something that did not happen. `publish` records inside
  the supersede loop as well as for itself — the rosters it demotes did not demote themselves.
- **Two `@Component` classes may not share a simple name.** Spring derives a bean name from it
  alone, so identically-named adapters in two contexts collide, and they collide at startup rather
  than at compile time. Hence `SchedulingAuditLog`, `WorkforceAuditLog`, `AccessAuditLog` and
  `NotificationAuditLog`.
- **Boot disables metrics export under test.** `/actuator/prometheus` answers 404 in a
  `@SpringBootTest` while working in production; `@AutoConfigureMetrics` opts back in, at the cost
  of a Spring context of its own. A test shares the suite's context only while its annotations
  match the others' exactly: `MetricsHttpTest` and the bare smoke test `HrSchedApplicationTests`
  (no `@AutoConfigureMockMvc`) each start another.

## Domain modelling

- **Entities vs value objects.** `Shift` and `Employee` are classes with identity equality,
  because two shifts with the same period — or two people with the same name — are still
  different things. `ShiftId`, `ShiftPeriod` and `EmployeeId` are records because they *are*
  their values. Do not turn an entity into a record.
- **Invariants live in constructors,** so illegal states cannot be represented and callers
  never have to check.
- **No working-time threshold is a constant**, in a class or a migration — the statute rule in
  `.claude/CLAUDE.md`.

## Testing

**`@ParameterizedTest` is an accepted idiom, for data-only rows.** `junit-jupiter-params` comes
with the Boot BOM; no dependency to add. Use it where the rows differ only in *data* and the
assertion sentence is identical — null-check batteries, blank-name batteries, the identity
records, exception-type lists, and *parsing* a value out of a request. Do **not** use it for a
permission or tenancy **decision**: who may do what, and who may see whose data. A parameterised
isolation test reads as a table and hides which row is the one whose failure is a security
incident. Nor for rows that differ in setup, or whose comment explains *why* — the comment then
belongs to no row. If a table passes about a dozen rows, split it.

## Formatting

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
| `spring-boot-starter-test` alone | a test starter per technology: `spring-boot-starter-webmvc-test`, `-actuator-test`, each bringing `spring-boot-starter-test` with it |
| `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| `@MockBean` | `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`) |
| `com.fasterxml.jackson.databind` | `tools.jackson.databind` (Jackson 3) |

Also imported here under names only the current majors use:
`org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics`, and
Testcontainers 2's non-generic `org.testcontainers.postgresql.PostgreSQLContainer` (1.x:
`org.testcontainers.containers.PostgreSQLContainer<?>`).

When unsure, inspect the jar (`unzip -l ~/.m2/repository/...`) or run `./mvnw dependency:tree`.
Do not guess.
