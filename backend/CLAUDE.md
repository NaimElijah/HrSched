<!-- Maintainers: loads when a file under backend/ is first read or edited, on top of
.claude/CLAUDE.md. Only what backend work needs, under 200 lines; anything a session must know
before opening a file belongs in .claude/CLAUDE.md. Stripped before Claude sees the file. -->

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
  needs one, and, for a context that consumes another's published port, a package named for what it
  consumes: `workforce/`, and `notification/` for the `notifications` context. May use Spring. Each
  `Jdbc*Repository` owns its own SQL; there is deliberately no shared helper package, because
  `ContextPackages` would treat a new top-level package as a bounded context and every context
  touching it would fail the cross-context rule.

Contexts today: `scheduling` (shifts, rosters, constraints, generation), `workforce` (employees,
qualifications, availability), `access` (organisations, accounts, sessions and tokens),
`notifications` (what somebody has been told), beside `configuration/` and `HrSchedApplication`.
`compose.yaml`, the local database, sits in `backend/` because Boot looks in the working directory.

A context may reference another context's `application.port.in` package and **nothing else**
(ADR-008). Consuming one belongs in an `adapter/out/<that context>/` class, and there are three:
`scheduling/adapter/out/workforce/WorkforceDirectoryAdapter`,
`scheduling/adapter/out/notification/NotificationAdapter` and
`access/adapter/out/workforce/WorkforceEmployeeDirectory`.

## Architecture tests

- **Dependencies point inward, and contexts are independent. Both are enforced.**
  `HexagonalArchitectureTest` fails the build if the domain depends on anything but the JDK and
  HrSched's own domain packages; the application on anything but the JDK, SLF4J and HrSched's
  packages outside the adapters and `configuration`; either reaches a database, a network, a
  remote object, a disk, a stream or another program through the JDK (`java.sql`, `java.net` but
  `URI`, `java.rmi`, `java.nio.file`, `java.nio.channels`, `java.io`'s streams, readers, writers
  and files but the in-memory ones, `ZipFile`, `ProcessBuilder`, `Process`, `Runtime`), so a port
  takes no `OutputStream` either, as the adapter does the I/O; a context reaches past another's
  published ports, or reaches them from outside its `adapter/out`; a class in `configuration`
  other than the `@Configuration` wiring reaches past them; the kernel depends on a context;
  contexts, or a context's layers, form a cycle; a class in a context sits outside its `domain`,
  `application` and `adapter` packages; a class other than `HrSchedApplication` sits in the root
  package; a `@RestController` is handled by no `@RestControllerAdvice` of its own context, which
  lists it in `assignableTypes` or holds it in its `basePackages` or `basePackageClasses`; or a
  method other than a `save…` on a driven-port `*Repository` or `*Directory` takes no
  `OrganisationId` and is not in the named `UNSCOPED_METHODS`. Never weaken or delete a rule to
  make code pass — the rule is the point. Deleting one that provably cannot fail is different, and
  needs the proof.
- **When adding an architecture rule, watch it fail first.** All twenty rules here have been
  observed failing on a deliberately introduced violation, then reverted; a rule that has only
  ever been green proves nothing. A rule whose logic is ours keeps that failure as a test in
  `HexagonalArchitectureRulesTest`, against a class under `architecture/fixtures` that breaks it
  on purpose, beside the nearest legal case. The same applies to a test: a fix whose test was
  never seen failing has not been shown to fix anything.

## Adding a use case

Pick the context → domain concept → port in/out → service (no annotation) → register in
`UseCaseConfiguration` → adapters → decide who may call the route in `SecurityConfiguration` →
tests. Two steps fail late rather than at compile time: a forgotten bean registration fails at
startup, and a controller left out of its context's `@RestControllerAdvice(assignableTypes = …)`
fails the architecture test.

## Acting identity, tenancy and the security chain

- **Who is acting is an argument, never an ambient read.** `SecurityContextHolder` is forbidden
  outside `configuration.security`, where one Spring callback has no parameter to take the caller
  from. A use case takes an `Actor`; a controller gets one through `@AuthenticationPrincipal` and
  the organisation from it through `actingIn` (`organisationOf` in `AccountController`).
  `GET /api/organisations/{id}` names what to read, not who acts; a tenant naming another gets a
  404. Nothing reads `X-User`, `X-Role`, `X-Organisation` or `X-Employee`; never bring them back.
- **Tenancy is in every signature.** Repository lookups take an `OrganisationId`; there is no
  `findById(EmployeeId)`. The exceptions are the access context's credential reads and its register
  of organisations, which ADR-055 lists with its reasons. Use cases take an `Actor` and call
  `requireAccessTo` first. A cross-tenant attempt is a 404, never a 403 — a 403 confirms the
  resource exists.
- **`UserId` and `EmployeeId` are never compared.** They are different identities on purpose. To
  ask whether an actor is the person being acted on, use `TenantActor.isThemself` — the link is
  carried, not inferred from matching UUIDs (ADR-012).
- **Only a Scheduler builds rosters.** `MAY_SCHEDULE` is `{SCHEDULER}`; an Admin manages people,
  qualifications and the constraint set. The role javadoc and the requirements table agree, and
  the permission sets must follow them rather than the other way round.
- **`SecurityConfiguration` ends in `denyAll`.** Tenant routes are matched by family — the
  `/**` prefixes in `TENANT_ROUTES` — so a new endpoint under an existing prefix is **already
  open** to every tenant role, and only a new prefix is refused until named. Either way, who may
  call it is a decision, not a formality. The URL rules are the coarse outer line; the use case
  behind each route is what decides.

## Persistence

Persistence is settled (ADR-004): PostgreSQL, hand-written SQL through `JdbcClient`, Flyway, one
schema with `organisation_id` on every aggregate root, bar the operator's account row (ADR-029) and
the session tables, which carry none (ADR-055); child tables are reached only through their root.
The domain may not carry `jakarta.persistence` annotations — the architecture test fails on them —
so JPA would need a parallel annotated model, buying only what an adapter must not leak.

- **Migrations** in `src/main/resources/db/migration/` are the only way the schema changes. Name
  one `V<n>__<rule_in_snake_case>.sql`, opening with a comment giving the rule, ADR and reasoning.
  **Never edit an applied migration**; correct it in the next, as V6 and V8 do. A backfill is
  explicit and invents nothing. An index on a root leads with `organisation_id`; a foreign key
  stays inside one context, bar the one every root table carrying an `organisation_id` has to
  `organisation (id)`, the tenant register `access` owns; the session tables carry none (ADR-004,
  ADR-055). A migration rewriting stored rows gets a test that migrates to
  the version before, inserts rows as it stored them, then migrates on (see
  `AppendOnlyRosterHistoryMigrationTest`).
- **Conflicts come from the database, not from version columns.** A use case changing an aggregate
  reads it with `findForUpdate`, so a concurrent change waits and both apply (ADR-041). A published
  save's exclusion violation or deadlock (23P01, 40P01) is a 409; a revision losing a race lets its
  unique violation surface, never `ON CONFLICT DO NOTHING`. Upserts keep `organisation_id` equal to
  `EXCLUDED`'s, and a write touching no row is refused.

## Transactions, concurrency and time

- **Every use case is one transaction**, wrapped in `UseCaseConfiguration` rather than annotated,
  because the architecture test forbids Spring in the application layer (ADR-023). The wrapper,
  `TransactionalUseCases`, is a subclass proxy: a service must stay non-final, a service calling
  its own method bypasses it, and only methods named `find…` run read-only. **The one exception is
  `startGeneration`** (ADR-045, ADR-046): the proxy leaves it alone, and it marks out its own units
  through `UnitsOfWork`, so the generation commits before its solve is handed to a thread and the
  solve holds no connection. Do not add another without an ADR.
- **Generations run on `GenerationRunner`'s threads, but not under test.** The shared test context
  (`PostgresBackedApplication`) replaces it with a runner that works on the calling thread, so an
  HTTP test's generation has ended by the time the request starting it is answered.
- **Publications within an organisation take turns.** `RosterRepository.lockPublicationsOf` takes
  a transaction-scoped advisory lock, before any read the decision depends on: in `publish`, and
  in raising, approving and rejecting a swap (ADR-027, ADR-039). It throws outside a use case's
  transaction. R35 has no schema backstop; the lock is all that holds it (ADR-033).
- **Roster history is append-only** (R37, ADR-037). A roster's status is not stored but derived
  from `published_at` and `superseded_at`, each written once, at the moment
  `Roster.nextMomentOfRecord` gives.
- **Time is an input.** A use case reads an injected `Clock` (`Clock.systemUTC()` in
  `UseCaseConfiguration`), never `Instant.now()` or the database's clock. A `Shift` carries its own
  `ZoneId`, with no default: working-time days are counted in that zone, instants are stored as
  UTC, and the HTTP boundary validates the zone id (R36, ADR-034).

## Audit and instrumentation

- **Instrumentation lives in adapters, and three rules enforce it.** Nothing prints anywhere; the
  domain may not touch `org.slf4j`, `ch.qos.logback` or `java.util.logging`; neither the domain
  nor the application layer may touch `io.micrometer` or `io.opentelemetry`. So `@Timed` and
  `@Counted` cannot go on a use case; a service records through a driven port (ADR-016 to 018).
- **Personal data is unrepresentable in a log, not merely discouraged.** `AuditedChange` carries a
  bare `UUID` subject so a name cannot be written, and each context's `*AuditLogTest` asserts that
  nothing it writes is personal data. Attach fields with slf4j `addKeyValue`, never by
  interpolating them: the message is a constant, and everything variable is a field the ECS
  encoder publishes as JSON. A domain exception raised over personal data carries a constant
  message too: its message becomes a 400's detail and, if it escapes, a log line, so it names
  neither a span nor hours.
- **The audit port is per context on purpose.** Each of the four contexts owns an `AuditTrail`,
  an `AuditedChange` with its own change enum, and a `*AuditLog` adapter; access's change carries
  an `Optional<OrganisationId>`, as an operator's act belongs to no organisation. Different types,
  not duplication — anything shared would have to enter the kernel ADR-008 keeps small.
  `UseCaseConfiguration`, where they collide, writes three out in full.
- **Refuse before the first write; record after the save.** An audit line outlives the rollback of
  a refusal that came after it (#133): every check that can refuse runs before anything is written,
  and every write that can still fail runs before anything is recorded. Telling somebody is such a
  write: the notifier stores a row in the use case's transaction, so a use case tells before it
  records. `publish` saves the supersessions, its own roster and the swap decisions, tells the
  parties of a lapse or an approval, and only then records — one `ROSTER_SUPERSEDED` per demoted
  roster, because the rosters it demotes did not demote themselves. One breach no ordering removes:
  `NotificationService.notify` records `NOTIFICATION_RAISED` straight after its own save, inside
  the caller's transaction, so a later telling that fails leaves an earlier one's line behind (#31).
- **Two `@Component` classes may not share a simple name.** Spring derives a bean name from it
  alone, so same-named adapters in two contexts collide at startup rather than at compile time.
  Hence `SchedulingAuditLog`, `WorkforceAuditLog`, `AccessAuditLog`, `NotificationAuditLog`.
- **Boot disables metrics export under test**, so `/actuator/prometheus` answers 404 in a
  `@SpringBootTest`; `@AutoConfigureMetrics` opts back in. In production nothing can read it or
  `/actuator/metrics`: the chain answers every caller 401 without a token and 403 with any token
  (ADR-049, #78); `MetricsHttpTest` asserts it.

## Domain modelling

- **Entities vs value objects.** `Shift` and `Employee` are classes with identity equality,
  because two shifts with the same period — or two people with the same name — are still
  different things. `ShiftId`, `EmployeeId` and `TimeRange` (a shift's period) are records because
  they *are* their values. Do not turn an entity into a record.
- **Invariants live in constructors,** so illegal states cannot be represented and callers
  never have to check.
- **No working-time threshold is a constant** — the statute rule in `.claude/CLAUDE.md`.

## Testing

- **One container, one context.** `PostgresDatabase` holds a container started once and never
  stopped. `PostgresBackedApplication` hands Spring its address as a `JdbcConnectionDetails`,
  never the container as a bean: Boot's Testcontainers lifecycle stops every container bean when
  its context closes, `destroyMethod = ""` or not, so a test closing a context it started would take
  the database from every test after it (#252, pinned by `PostgresBackedApplicationTest`). A
  Spring test shares the suite's context only while its annotations are exactly
  `@Import(PostgresBackedApplication.class) @SpringBootTest @AutoConfigureMockMvc`, as the smoke
  test `HrSchedApplicationTests` does. Two more are started: `MetricsHttpTest`'s, and one on a real
  port that `MultipartRequestHttpTest`, `FormPostLimitHttpTest` and `ErrorDispatchHttpTest` share
  by adding `webEnvironment = RANDOM_PORT`. Two tests start an application themselves:
  `JsonDocumentLimitStartupTest`, passing the datasource as properties, and
  `PostgresBackedApplicationTest`, which starts and closes one over `PostgresBackedApplication` on
  purpose, to pin that closing it leaves the database answering.
- **Isolation.** An HTTP test onboards a fresh organisation through `TestTenants`; an adapter
  test, which runs without Spring, calls `PostgresDatabase.empty()` first.
- **Fakes are hand-written**, package-private beside the service tests that use them, and copy the
  real adapter's refusals — a fake that accepts what the database refuses tests nothing.
- **Invented values are labelled** in the class javadoc and again on each value.
- **`@ParameterizedTest` is for data-only rows** (`junit-jupiter-params` comes with the Boot BOM):
  rows that differ only in *data* under an identical assertion sentence — null-check and
  blank-name batteries, identity records, exception-type lists, *parsing* a request value. **Not**
  for a permission or tenancy **decision**: a parameterised isolation test reads as a table and
  hides which row's failure is a security incident. Nor for rows differing in setup, or whose
  comment explains *why* — the comment then belongs to no row. Past about a dozen rows, split it.

## Formatting

google-java-format is authoritative and not configurable — 2-space indent, fixed wrapping. Never
hand-format; run `./mvnw spotless:apply`. `pom.xml` is indented with tabs (Spring Initializr's
convention); `.editorconfig` encodes both, deliberately.

## Spring Boot 4.x renamed things

Training data and most tutorials use the 3.x names, which will not compile here (verified against
this project's jars):

| 3.x | 4.1.2 |
| --- | --- |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `spring-boot-starter-test` alone | a test starter per technology: `spring-boot-starter-webmvc-test`, `-actuator-test`, each bringing `spring-boot-starter-test` with it |
| `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| `@MockBean` | `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`) |
| `com.fasterxml.jackson.databind` | `tools.jackson.databind` (Jackson 3) |

Also imported here under names only the current majors use:
`org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics`, and
Testcontainers 2's non-generic `org.testcontainers.postgresql.PostgreSQLContainer` (1.x:
`org.testcontainers.containers.PostgreSQLContainer<?>`). When unsure, inspect the jar
(`unzip -l ~/.m2/repository/...`) or run `./mvnw dependency:tree`. Do not guess.
