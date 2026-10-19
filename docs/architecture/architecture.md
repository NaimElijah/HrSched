# Architecture — HrSched

This document records the system **as it actually is**. It is descriptive, never aspirational:
a plan for something not yet built belongs in an [ADR](../ADRs/) as a *Proposed* decision.

For *what the system must do*, see [requirements.md](../requirements/requirements.md); for *why*
these choices were made, see the [ADRs](../ADRs/).

> **Current state:** the domain is modelled and works end to end — onboard an organisation, define
> people and shifts, configure rules, generate a roster, adjust it by hand, publish it, and swap a
> shift — and it is all stored in PostgreSQL, so it survives a restart. People sign in, and every
> request is authenticated by a token HrSched signed and authorised against the account it names.
> What is still unfinished is the part the product is named for: **generation is greedy, not
> optimised** (see [ADR-005](../ADRs/ADR-005-roster-generation-behind-a-port.md)).

---

## Architecture overview

The repository holds `backend/` and `frontend/` as siblings with independent builds
([ADR-007](../ADRs/ADR-007-monorepo-backend-and-frontend.md)), orchestrated from the root by
`scripts/run.mjs` ([ADR-014](../ADRs/ADR-014-root-task-runner.md)).

`frontend/` is a React 19 single-page application built by Vite
([ADR-013](../ADRs/ADR-013-react-typescript-vite-frontend.md)). In development its dev server
proxies `/api` to the backend on 8080, so the browser sees one origin and **no CORS configuration
exists anywhere in this repository**.

The backend is a single Spring Boot process serving HTTP on 8080. Its code is organised **by
bounded context**, each context a top-level package holding its own complete **hexagon**
([ADR-006](../ADRs/ADR-006-organise-packages-by-bounded-context.md)).

```mermaid
flowchart TB
    client[HTTP client]

    subgraph scheduling [scheduling context]
        schedWeb["adapter.in.web<br/>SchedulingController"]
        schedApp["application<br/>SchedulingService"]
        schedDom["domain.model<br/>Shift, Roster, ConstraintSet"]
        acl["adapter.out.workforce<br/>WorkforceDirectoryAdapter"]
        gen["adapter.out.generation<br/>GreedyRosterGenerator"]
        schedStore["adapter.out.persistence<br/>JdbcRosterRepository, JdbcShiftRepository"]
    end

    subgraph workforce [workforce context]
        wfWeb["adapter.in.web<br/>EmployeeController"]
        wfPub["application.port.in<br/>FindRosterableEmployeesUseCase"]
        wfDom["domain.model<br/>Employee, Qualification"]
        wfStore["adapter.out.persistence<br/>JdbcEmployeeRepository"]
    end

    subgraph notifications [notifications context]
        ntfWeb["adapter.in.web<br/>NotificationController"]
        ntfPub["application.port.in<br/>NotifyUseCase"]
        ntfDom["domain.model<br/>Notification"]
    end

    subgraph access [access context]
        accWeb["adapter.in.web<br/>OrganisationController"]
        accApp["application<br/>OrganisationService"]
        accDom["domain.model<br/>Organisation, Account"]
    end

    kernel["shared.domain.model<br/>OrganisationId, Actor, TimeRange, EmployeeId"]
    db[("PostgreSQL<br/>one schema, organisation_id on every root")]

    client --> schedWeb
    client --> wfWeb
    client --> accWeb
    client --> ntfWeb
    ntfWeb --> ntfPub
    ntfPub --> ntfDom
    notifications --> kernel
    accWeb --> accApp
    accApp --> accDom
    schedWeb --> schedApp
    schedApp --> schedDom
    schedApp -.via port.-> acl
    schedApp -.via port.-> gen
    acl -->|published port only| wfPub
    wfPub --> wfDom
    scheduling --> kernel
    workforce --> kernel
    access --> kernel
    schedApp -.via port.-> schedStore
    wfPub -.via port.-> wfStore
    schedStore --> db
    wfStore --> db
```

Three rules hold this together, and each fails the build when broken:

1. Inside a context, dependencies point inward. The domain imports nothing but the JDK.
2. A context may reference another context's `application.port.in` package and nothing else.
3. The shared kernel depends on no context.

## Components

| Component | Responsibility |
| --- | --- |
| `shared.domain.model` | The kernel: `OrganisationId`, `UserId`, `EmployeeId`, `QualificationId`, `Role`, `TimeRange`, and the sealed `Actor` (`TenantActor`, `Operator`). Identities and pure values that cross contexts, with no behaviour of their own. A `TenantActor` also carries the `EmployeeId` it is, when it is one ([ADR-012](../ADRs/ADR-012-acting-identity-carries-its-employee-record.md)). |
| `access.domain.model` | `Organisation` — the tenant itself — and `Account`, what signs in. An account's `Membership` is sealed into one organisation in one role, or the platform operator, so an operator carrying a role or an employee link is unrepresentable. So is an Employee account that may be used while linked to nobody (R34): the only one linked to nobody at all was stored before the rule, and migration `V9` disabled it until it is linked. `SignInName` and `PasswordHash` both print as redacted. Otherwise deliberately small: the tenancy *types* live in the kernel because every context needs them. |
| `access.application` | Signing in, renewing and ending a session, reading one's own session and changing one's own password (F14); bootstrapping the platform operator; administering an organisation's own accounts (F15); and onboarding, listing and reading organisations (F13), with the register they live in. Onboarding creates the organisation and its first Admin account in one transaction, so a refused account leaves no organisation behind; that account is created needing a password change, because the one it was given was typed by somebody else. Account administration takes the organisation's advisory lock before it reads, because R14 — an organisation keeps at least one active Admin — is decided by reading and then writing. Onboarding and listing are the platform `Operator`'s alone — the guard is the inverse of every other one here, asking whether the actor belongs to a tenant rather than which role they hold in it. See [ADR-019](../ADRs/ADR-019-an-application-layer-for-the-access-context.md). |
| `notifications.domain.model` | `Notification` — something one person has been told, and whether they have seen it. It carries identifiers, a kind, a time and a flag, and **there is nowhere in it to put a sentence or a name** ([ADR-021](../ADRs/ADR-021-notifications-as-a-bounded-context.md)). |
| `notifications.application` | Raising and reading notifications. `NotifyUseCase` is this context's **published API** and is deliberately not on a controller; `ReadNotificationsUseCase` is strictly self-service, and whose inbox is read comes from the acting identity rather than from any parameter. Takes a `Clock`, so when something happened is an input rather than an ambient fact. |
| `workforce.domain.model` | `Employee` (aggregate root: qualifications, unavailability, preferences, contract terms), `Qualification`, `ShiftPreference`, `ContractTerms`. |
| `workforce.application` | Employee and qualification use cases. `FindRosterableEmployeesUseCase` is this context's **published API**, returning `RosterableEmployee` rather than the aggregate. |
| `scheduling.domain.model` | `Shift`, `Roster` (aggregate root), `Assignment`, `ConstraintSet` and the sealed `WorkingTimeConstraint` family, `EmployeeSchedule` and the `WorkingPeriod`s it holds, `HeldWork`. |
| `scheduling.application` | Shift definition, constraint configuration, roster generation and publication. Assembles the evidence constraints are judged against. |
| `scheduling.adapter.out.workforce` | An anti-corruption layer: one of **two** classes in scheduling permitted to name another context, and only its published port. |
| `scheduling.adapter.out.notification` | The other one. Translates "a swap was approved" into the word the `notifications` context uses for it, so nothing inward of the `Notifier` port knows that context exists ([ADR-021](../ADRs/ADR-021-notifications-as-a-bounded-context.md)). |
| `scheduling.adapter.out.generation` | `GreedyRosterGenerator` — a placeholder implementation of the generation port. It also records coverage (`hrsched.shifts.required` and `hrsched.shifts.uncovered`), because it is the only place that still knows both what was asked for and what could legally be filled. |
| `*.adapter.out.persistence` | `Jdbc*Repository` — hand-written SQL through `JdbcClient`, one per driven port, every read carrying its organisation in the `WHERE` clause. A `findForUpdate` holds the root's row until the use case's transaction ends, and refuses outside a writable one ([ADR-041](../ADRs/ADR-041-make-concurrent-changes-take-turns-on-the-row.md)). The one exception is named as such in its port: `JdbcAccountRepository.findBySignInName`, which sign-in needs because a submitted name is all it has to go on. No ORM: the domain may not be annotated, so mapping is explicit ([ADR-004](../ADRs/ADR-004-postgresql-behind-the-driven-ports.md)). |
| `*.adapter.out.audit` | `SchedulingAuditLog`, `WorkforceAuditLog`, `AccessAuditLog` and `NotificationAuditLog` — one per context, each implementing its own context's `AuditTrail` port and turning one recorded change into a structured log event and a counter. All four are named for their contexts because Spring derives a bean name from the simple class name alone, so four classes called `AuditLog` would collide at startup rather than at compile time. |
| `configuration` | Spring wiring. The only place permitted to see more than one context, which is why the rules exempt `@Configuration`-annotated classes and nothing else. |
| `access.adapter.out.workforce` | `WorkforceEmployeeDirectory` — an anti-corruption layer, and the **only** class in access permitted to name another context. It asks workforce's published port whether an organisation employs somebody and reduces the answer to a boolean on the spot, so a colleague's name never enters the context that writes the access audit trail. |
| `access.adapter.in.token` | `AccessTokenDecoder` (the application's only `JwtDecoder`), `ActorAuthenticationConverter` and `ActorAuthentication`. Verifying a token and deciding who is acting both live here, in the context that owns accounts, rather than in `configuration` — which may not name an adapter class, so the chain receives them by type. |
| `access.adapter.out.token` | `AccessTokenKey` (the RSA key pair, or a refusal to start without one), `AccessTokenSigning`, `JwtAccessTokenIssuer` and `SecureRandomSessionSecrets`. |
| `access.adapter.out.password` | `BCryptPasswordHasher` — a `DelegatingPasswordEncoder`, so every stored hash names the algorithm that made it and a later change needs no migration. |
| `access.adapter.in.bootstrap` | `OperatorBootstrap` — an `ApplicationRunner` that creates the first platform operator from configuration, and only if no account holds the name ([ADR-029](../ADRs/ADR-029-provision-accounts-from-the-top-down.md)). |
| `configuration.security` | `SecurityConfiguration` (the one filter chain), `ProblemAuthenticationEntryPoint` and `ProblemAccessDeniedHandler` (which write the 401 and 403 bodies a filter would otherwise leave empty), and `SecurityPolicyProperties`. |
| `configuration.observability` | Cross-cutting instrumentation that belongs to no context. Today that is `TraceIdResponseFilter`, which puts the request's trace id on the response as `X-Trace-Id`. It depends on nothing about who is calling, so it can run ahead of the security chain, and it is ordered to (see *Structural decisions not visible from the file layout*), which is why a refusal still carries the id. |
| `configuration.web` | `RequestBindingExceptionHandler` — answers a request Spring could not bind to a controller's parameters (a missing query parameter, an identifier that does not parse) with a 400 problem detail naming what was wrong, whose detail repeats nothing that was sent. It belongs to no context because the failure happens before any controller runs ([ADR-024](../ADRs/ADR-024-answer-unbindable-requests-with-a-problem-detail.md)). |
| `frontend/src/api` | The typed client. `client.ts` is the only module that calls `fetch` or knows a header exists — it attaches the bearer token to every request except renewing and signing out, whose credential is the refresh cookie and which a stale token would get refused (#179), renews the session once on a 401 from anything credentialed by the token, the session read and the password change included and retries the request exactly once (single-flight within the tab, serialised across tabs by a Web Lock), and reads back `X-Trace-Id`; `session.ts` holds the access token in a module variable and **nowhere a script can read it back**, and notifies the shell when a session ends. It writes no identity claim of any kind: the bearer token is the whole of what says who is acting, and a request carrying a second, weaker answer would be offering one. `problem.ts` validates the RFC 9457 body into a typed `ApiError`, which carries that trace id as the reference a refusal can be quoted by; `scheduling.ts`, `workforce.ts`, `access.ts` and `notifications.ts` mirror the backend DTOs, one module per context. |
| `frontend/src/auth` | `SessionProvider` (which of five states the app is in, and the handshake every load begins with, including the one that cannot finish, because the backend was not reached or the session could not be read back), `SignInPage`, `ChangePasswordPage` and `SessionBar`. Replaced `frontend/src/identity`, whose bar let anybody claim any role in any organisation because the backend believed whatever headers it was sent. |
| `frontend/src/pages` | One screen per capability: People, Accounts, Qualifications, Rules, Shifts, Rosters, Swaps, Me, Organisations. Organisations is the platform Operator's: it lists and onboards organisations, and offers no way to act inside one, because the Operator is refused everything inside every tenant ([ADR-030](../ADRs/ADR-030-confine-the-operator-to-the-register-of-organisations.md)). A tenant role reaching it is shown the 403 rather than a hidden tab. Accounts is its mirror for an Admin: it creates accounts, changes what one is, disables and restores one, and resets a colleague's password. Nothing on it names an organisation, because the backend takes that from the token; every temporary password is typed, masked, never defaulted, and cleared once the request it was typed for has succeeded. Swaps is where a Scheduler answers requests; approving publishes a replacement roster, so it is refused exactly as any publication is: with every broken working-time rule named, or, if it would put somebody in two places at once, with that. Me shows an employee their identifier, their published work over the planning span, and a way to hand a shift to a colleague named by that colleague's identifier, because an employee may not list colleagues (Q25). Me also holds the employee's `Inbox`. Opening a roster shows `RosterBoard`, which loads that roster by identifier and changes a draft through a picker and buttons per shift rather than by drag and drop ([ADR-025](../ADRs/ADR-025-edit-a-draft-roster-with-controls-not-drag-and-drop.md)). |

## Communication

Inbound HTTP only; nothing calls out. Contexts talk in-process, through published driving ports,
by direct method call. There are no events and no queues.

The browser talks to the backend over JSON on `/api`. In development that request goes to the Vite
dev server, which proxies it; in production the built bundle is served however the deployment
chooses and the same paths apply. The backend is unaware of either arrangement.

## Authentication

**Every route under `/api` requires a token**, except the three that are authentication itself:
`POST /api/auth/sign-in`, `/refresh` and `/sign-out`. Who is acting is the account the token names,
and every controller takes it from `@AuthenticationPrincipal Actor`
([ADR-011](../ADRs/ADR-011-issue-our-own-access-tokens.md)).

**The organisation comes from the account, never from the request.** Each web adapter has one
`actingIn(actor)` helper over `Actor.requireTenantMembership()`, and that helper is the only way an
organisation enters a request — there is no identifier in a path, body or header for a caller to
change ([ADR-031](../ADRs/ADR-031-take-the-organisation-from-the-account.md)). That also makes the
platform operator's refusal structural rather than a check: it belongs to no organisation, so there
is nothing inside one for it to act in ([ADR-030](../ADRs/ADR-030-confine-the-operator-to-the-register-of-organisations.md)).
The one exception is `GET /api/organisations/{id}`, where the target is in the path because the
register is what the operator administers.

A request carrying an invalid token is refused on every route, including the ones that do not
require one — that is the bearer filter's own behaviour, and why a client must send no token rather
than a stale one.

**The chain is closed: it ends in `denyAll`.** Every route this application serves is named by a
rule, and anything else is refused — including a route somebody adds later and forgets to decide
about. The rules are the coarse outer line; the use case behind each route is what actually decides
([ADR-032](../ADRs/ADR-032-close-the-security-chain-by-default.md)).

| Route | Who may reach it |
| --- | --- |
| `POST /api/auth/sign-in`, `/refresh`, `/sign-out` | anybody — authentication itself |
| `/actuator/**` | anybody; what exists there is decided by the exposure list (ADR-017) |
| `GET /api/auth/session`, `POST /api/auth/password` | any authenticated account, including one whose password change is still pending |
| `POST`/`GET /api/organisations` | `OPERATOR` |
| `GET /api/organisations/{id}` | any role; the use case decides whose |
| `/api/accounts/**` | `ADMIN` |
| `/api/employees/**`, `/api/qualifications/**`, `/api/shifts/**`, `/api/constraints/**`, `/api/rosters/**`, `/api/me/**`, `/api/swaps/**`, `/api/notifications/**` | any tenant role |
| anything else | nobody |

**A verified signature is not enough, and that is the point.** The decoder proves a token is one
this service signed, for this service, unexpired, of type `at+jwt`, and naming both an account and
a session. It cannot know whether the account has since been disabled, whether its role changed, or
whether the session was signed out a second ago — so every authenticated request asks the database,
and a token whose account or session no longer stands is refused. That is what makes disabling,
demoting and signing out take effect on the next request rather than whenever a token happens to
expire, and it costs one read per request.

```mermaid
sequenceDiagram
    participant B as Browser
    participant A as AuthController
    participant S as SessionService
    participant D as AccessTokenDecoder
    participant V as ActorAuthenticationConverter
    participant DB as PostgreSQL

    B->>A: POST /api/auth/sign-in
    A->>S: signIn(name, password)
    S->>DB: account by sign-in name, then open a session
    S-->>A: access token + refresh token
    A-->>B: 200 {accessToken} + Set-Cookie __Secure-hrsched-refresh

    B->>D: GET /api/auth/session, Authorization: Bearer …
    D->>D: RS256 pinned, typ/iss/aud/exp/iat/sub/sid checked
    D->>V: verified Jwt
    V->>S: findActor(sub, sid)
    S->>DB: one query joining account and sign_in_session
    S-->>V: Actor, or nothing
    V-->>B: 401 if nothing, otherwise the request proceeds

    B->>A: POST /api/auth/refresh (cookie + X-Requested-With, no bearer)
    A->>S: refresh(token)
    S->>DB: mark replaced WHERE replaced_at IS NULL, then issue a successor
    S-->>A: new access token + rotated refresh token
    A-->>B: 200 + rotated cookie, or 401 and the cookie cleared

    B->>A: POST /api/auth/sign-out (cookie + X-Requested-With, no bearer)
    A->>S: signOut(token)
    S->>DB: revoke the session
    A-->>B: 204 + cookie cleared
```

Signing in, renewing and signing out are open by design: the credential for each is what the caller
presents. The session endpoints are reachable by an account whose temporary password is still
pending, because they are the two things such an account has to be able to do — it holds
`PASSWORD_CHANGE_REQUIRED` and no role at all, so every rule written in terms of a role refuses it.

## Data flow

Generating and publishing a roster:

```mermaid
sequenceDiagram
    participant C as Scheduler
    participant W as SchedulingController
    participant S as SchedulingService
    participant D as WorkforceDirectoryAdapter
    participant F as workforce (published port)
    participant G as RosterGenerator
    participant R as ConstraintSet

    C->>W: POST /api/rosters
    W->>S: generateDraft(actor, period)
    S->>S: actor.requireAccessTo(organisation)
    S->>D: candidatesFor(actor, organisation)
    D->>F: findRosterable(actor, organisation)
    F-->>D: RosterableEmployee[]
    D-->>S: RosterCandidate[]
    S->>G: generate(shifts, candidates, constraints)
    G-->>S: assignments
    S-->>C: 201 DRAFT roster

    C->>W: POST /api/rosters/{id}/publication
    W->>S: publish(actor, organisation, roster)
    S->>S: roster.requireDraft()
    alt not a draft
        S-->>C: 400, before anything is superseded or recorded
    end
    S->>R: violationsFor(each employee schedule)
    alt any rule broken
        R-->>C: 422 with every violation listed
    else compliant
        S-->>C: 200 PUBLISHED, recording the constraint version
    end
```

Everything is persisted, and the whole exchange above is one transaction: the boundary is the use
case, so a refusal at any point stores nothing ([ADR-023](../ADRs/ADR-023-the-transaction-boundary-is-the-use-case.md)).
An audit entry is a log line, though, and no rollback takes one back. So publication makes every
refusal it can before it supersedes and records anything. The one refusal that can still follow
those entries is `RosterPublicationConflictException`, which needs a writer that skipped the
publication lock ([ADR-027](../ADRs/ADR-027-take-turns-publishing-within-an-organisation.md)). A durable
trail is [#31](https://github.com/NaimElijah/HrSched/issues/31).

## External dependencies and integration points

**At runtime: PostgreSQL, and nothing else.** No broker and no third-party API. The schema is
created and changed only by the Flyway migrations in `backend/src/main/resources/db/migration`,
applied on startup. Locally `backend/compose.yaml` supplies the database and Boot starts it; a
deployment supplies `SPRING_DATASOURCE_URL`, `_USERNAME` and `_PASSWORD`.

| Dependency | Used for | Justification |
| --- | --- | --- |
| `spring-boot-starter-webmvc` | HTTP boundary, embedded Tomcat | ADR-001. Boot 4.x renamed this from `spring-boot-starter-web`. |
| `spring-boot-starter-actuator` | Health and readiness probes | Required of every service by the project baseline. A `db` health contributor now appears with the datasource. |
| `spring-boot-starter-security-oauth2-resource-server` | The security filter chain in front of every request, and signing and verifying the access tokens | [ADR-011](../ADRs/ADR-011-issue-our-own-access-tokens.md). Pulls in Spring Security and Nimbus JOSE+JWT, both Apache-2.0 and maintained on Boot's release train. Authentication is security-critical code nobody should hand-roll. `SecurityConfiguration` declares the one chain; a bearer token is verified on every request that carries one, and every route requires one except signing in, refreshing, signing out and `/actuator/**`. A route no rule names is refused ([ADR-032](../ADRs/ADR-032-close-the-security-chain-by-default.md)). |
| `spring-boot-starter-jdbc` | `JdbcClient`, the connection pool, and the transaction manager | [ADR-004](../ADRs/ADR-004-postgresql-behind-the-driven-ports.md). Chosen over JPA because the domain may not carry `jakarta.persistence` annotations. |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | Versioned schema migrations, applied on startup | ADR-004. New in Boot 4.x; 3.x had no Flyway starter. |
| `postgresql` | The JDBC driver | ADR-004. Runtime scope; nothing compiles against it. |
| `btree_gist` (PostgreSQL extension) | The `uuid` equality the `roster_one_published_per_moment` exclusion constraint indexes on | [ADR-026](../ADRs/ADR-026-make-overlapping-published-rosters-unrepresentable.md). Ships with PostgreSQL and is trusted, so the migrating role needs `CREATE` on the database, not superuser. Installed by migration `V2`; [ADR-037](../ADRs/ADR-037-keep-roster-history-as-a-record-in-time.md) widened the constraint in `V6`. |
| `spring-boot-docker-compose` | Starting the local database on `./mvnw spring-boot:run` | ADR-004, and it keeps `scripts/run.mjs` free of build logic as [ADR-014](../ADRs/ADR-014-root-task-runner.md) requires. Optional scope, so it never reaches a deployed artifact. |
| `testcontainers-postgresql`, `testcontainers-junit-jupiter`, `spring-boot-testcontainers` | The real database the tests run against | ADR-004. Docker is required to build; there is deliberately no skip-when-absent. Pinned by the Testcontainers BOM, which the Boot BOM does not manage. |
| `spring-boot-starter-opentelemetry` | A W3C trace id per request, in the MDC and on the response | [ADR-016](../ADRs/ADR-016-structured-logs-and-a-propagated-trace-id.md). Supplies the OpenTelemetry API, the Micrometer Tracing bridge and the OTLP exporters; export is switched off until Q19 is answered. |
| `micrometer-registry-prometheus` | Rendering the collected meters for a scraper to pull from `/actuator/prometheus` | [ADR-017](../ADRs/ADR-017-expose-metrics-to-be-scraped.md). Nothing pushes; the pushgateway is off. |
| `archunit-junit5` | Enforcing the hexagon and the context boundaries | Nothing else can fail a build on a dependency direction. |
| `spotless-maven-plugin` + `google-java-format` | Format check bound to `validate` | ADR-002. |
| `jacoco-maven-plugin` | Coverage measurement, reported and never enforced | [ADR-015](../ADRs/ADR-015-report-coverage-without-a-threshold.md). The version is pinned because the Boot 4.1.1 BOM manages none. |
| `@vitest/coverage-v8` (frontend) | The same, for the SPA | ADR-015. Pinned to the exact Vitest version by peer dependency. |

## Structural decisions not visible from the file layout

- **No working-time threshold is hardcoded anywhere.** A minimum rest period is a value in a
  `ConstraintSet`, never a constant. A jurisdiction is a *preset*: a named set of values loaded
  as data, whose numbers must be sourced from the statute. This is the single most important
  property of the model, and the reason the constraint types carry their own thresholds.
- **Constraints are hard; objectives do not exist yet.** `ConstraintSet` holds only rules that
  must never be broken. Fairness, preferences and disruption — what makes one legal roster better
  than another — are a separate concept that is not modelled.
- **Tenancy is in every signature, on purpose.** Repository lookups take an `OrganisationId`, so
  a cross-tenant read cannot be written by forgetting a filter. See
  [ADR-009](../ADRs/ADR-009-tenant-isolation-in-the-domain.md). A `save` takes no organisation of
  its own, so the upsert of every aggregate an organisation owns guards the write instead: its
  `ON CONFLICT … DO UPDATE` applies only where the stored row's organisation is the one being
  saved, and a save that so touched no row throws rather than overwrite another organisation's, or
  the operator's, row. The register of organisations is the exception, having no owner to compare.
- **A cross-tenant attempt returns 404, never 403.** A 403 would confirm the resource exists in
  another organisation.
- **Over HTTP that 404 is produced by the repositories, not by the tenant guard.** A request acts
  in the organisation its own account belongs to (ADR-031), so `Actor.requireAccessTo` compares an
  organisation with itself and cannot fail for a caller arriving over HTTP. Isolation there comes
  from every repository read filtering by organisation and returning nothing, which surfaces as an
  unknown-record 404. `CrossTenantAccessException` still guards every direct caller, and every
  exception handler still translates it. Worth knowing before anyone concludes the guard is dead
  code and deletes it.
- **The platform operator is refused inside an organisation, with a 403 rather than that 404.** The
  404 exists so a tenant cannot learn another organisation exists; an operator already knows,
  because listing them is the job. `NotATenantMemberException` carries that refusal and every
  exception handler translates it, counting it as `hrsched.access.denied{kind="operator_confined"}`.
  Reading one organisation from the register is the single documented exemption (ADR-030).
- **An organisation cannot be named at all, so it cannot be invented.** A request acts in the
  organisation of the account its token names, read from a row the caller cannot write. There used
  to be a boundary filter checking a claimed `X-Organisation` against the register; it was deleted
  when the last route stopped sending one, because a check that can never fire reads like
  protection and is not
  ([ADR-031](../ADRs/ADR-031-take-the-organisation-from-the-account.md), superseding ADR-020).
- **The servlet filters are explicitly ordered, and the numbers matter.**
  `TraceIdResponseFilter` must run after Boot's observation filter (registered at
  `HIGHEST_PRECEDENCE + 1`), which opens the span it reads — ordered any earlier, `currentSpan()` is
  null and `X-Trace-Id` silently disappears from every response. Spring Security's chain runs after
  it, at its own default order (`spring.security.filter.order`, −100), which is why a 401 from the
  security chain still carries a trace id.
- **There is one endpoint where that guard genuinely fires over HTTP.** On `GET
  /api/organisations/{id}` the acting identity comes from the token and the target comes from the
  path, so unlike every other route the two can differ, and a tenant naming somebody else's
  organisation is refused by the guard rather than by an empty repository read. It is the only place
  the branch is exercised outside a unit test
  ([ADR-019](../ADRs/ADR-019-an-application-layer-for-the-access-context.md)).
- **A `Location` header names an address that answers.** A created employee, qualification, shift,
  roster, swap request or organisation can be read back with `GET` at the address its 201 gave,
  authorised exactly as the listing it belongs to — reading one person takes an Admin or Scheduler
  because listing people does — so following a link never meets a looser or stricter rule than the
  list it came from. A swap request somebody is not party to is absent from their listing, so
  reading it directly answers them 404, as though it did not exist; one predicate in `SwapService`
  decides both.
- **Every log line is ECS JSON, in every environment including a laptop.** There is no profile that
  turns it off, because a format only production emits is one nobody reads until it matters. Each
  line carries the `trace_id` of the request that produced it, and the same value leaves on the
  response as `X-Trace-Id`, which the user interface shows on a refusal — so a person's report and
  the log are joinable. Nothing is exported: the OTLP exporters for traces, logs and metrics are all
  switched off until Q19 names somewhere to send them
  ([ADR-016](../ADRs/ADR-016-structured-logs-and-a-propagated-trace-id.md)).
- **Every change and every refusal is recorded, and no name is.** State-changing use cases call an
  `AuditTrail` driven port after saving; the two web exception handlers record refusals, because
  they are the only place that sees every one. An `AuditedChange` carries the organisation, the
  acting user, the kind of change and a bare `UUID` subject — a name cannot appear because there is
  nowhere to put one. A cross-tenant attempt, which the requirements call a security incident, is
  now a `WARN` and a counter while the caller still receives exactly the 404 it always did. Nothing
  is persisted and nothing is queryable; that is Q20
  ([ADR-018](../ADRs/ADR-018-audit-through-a-port-not-a-queryable-trail.md)).
- **Instrumentation lives outside the hexagon, and that is enforced.** No logger, meter or span
  appears in a domain or application class, and nothing anywhere prints: three architecture rules
  forbid `System.out` and `printStackTrace` outright, `org.slf4j`, `ch.qos.logback` and
  `java.util.logging` inside `..domain..`, and `io.micrometer` and `io.opentelemetry` inside either
  `..domain..` or `..application..`. Anything a use case needs recorded, it records through a driven
  port. The consequence worth knowing: Micrometer's `@Timed` and `@Counted` cannot be put on a use
  case.
- **Metrics are pulled, and nothing is pushed.** `/actuator/prometheus` publishes the JVM, process
  and `http.server.requests` meters Micrometer already collected, plus the product's own coverage
  measure. Exposure is an allow list: `loggers`, `env` and `heapdump` are shut, because there is no
  authentication and two of those disclose state while the third changes behaviour. Actuator shares
  port 8080 with the API, which is recorded rather than defended in
  [ADR-017](../ADRs/ADR-017-expose-metrics-to-be-scraped.md). No monitoring stack is committed;
  there is no environment yet to describe one for.
- **Coverage is measured and never enforced.** `./mvnw verify` writes `target/site/jacoco/`; the
  frontend reports only under `npm run test:coverage`. No threshold exists, on purpose
  ([ADR-015](../ADRs/ADR-015-report-coverage-without-a-threshold.md)).
- **`Actor` is sealed, and the tenancy guard returns what it proved.**
  `Actor.requireAccessTo(organisation)` hands back the `TenantActor` that may act there, decided by
  a `switch` over the sealed type. That shape is deliberate: the guard used to be a statement, and
  callers followed it with `if (actor instanceof TenantActor t)` before checking a role — an idiom
  that silently *skipped* the role check for an `Operator`. Returning the member leaves nothing to
  skip, and a third kind of actor cannot be added without deciding, in that one file, what it may
  reach. `Operator` reaches no organisation at all
  ([ADR-030](../ADRs/ADR-030-confine-the-operator-to-the-register-of-organisations.md)).
- **Aggregates are immutable.** Every change returns a new instance, so nothing is ever observed
  half-updated and invariants are checked in exactly one place.
- **Entities are classes, value objects are records.** `Shift`, `Roster`, `Employee`,
  `Organisation` and `Qualification` compare by identity alone; `TimeRange`, `ShiftId`,
  `ContractTerms` and the constraints are their values.
- **Constraint checking lives in the application layer**, not in `Roster`. It needs when each
  shift runs and who is available — data the aggregate cannot reach. The judgement itself is
  still the domain's.
- **Days are counted in each shift's own zone (R36).** A `Shift` carries a `ZoneId`, and
  `EmployeeSchedule` holds `WorkingPeriod`s — a period with its shift's zone — rather than bare
  `TimeRange`s, which stay zone-free in the kernel. It splits every period at midnight *in that
  period's zone* and totals the time landing on each day, so a shift running past midnight is
  counted against both days it touches, a shift ending exactly at midnight does not make the next
  day a working day, and a day is 23 or 25 hours long when the clocks change. Work in two zones
  lands on each zone's own date. Only ids the JVM's time zone database knows are accepted, so a raw
  offset such as `+01:00` is refused; there is no default, and shifts stored before V5 keep the UTC
  they were judged in. The Shifts page sends the browser's zone, so a site in another zone cannot
  yet be planned for
  ([#136](https://github.com/NaimElijah/HrSched/issues/136),
  [ADR-034](../ADRs/ADR-034-count-working-days-in-each-shifts-own-time-zone.md)).
- **`MaximumWeeklyWorkingTime` treats the roster period as the reference period.** Jurisdictions
  that average over a longer window cannot be expressed; that needs history beyond the roster.
- **The user interface enforces nothing.** Every rule is the backend's. The UI renders refusals —
  a 422 with its violations, a 422 listing none (an assignee without the qualification, a shift
  outside the roster's period), a 403, a 404 that may mean another tenant owns the resource — and
  it is written so those refusals read as outcomes rather than as bugs, but removing the UI removes
  no guarantee. A 422 is called a working-time breach only when it lists the rules it broke; the
  status alone does not say which kind of refusal it is.
- **Notification wording lives in the reader's interface.** The server stores a kind, a subject and
  a time, and holds no words ([ADR-021](../ADRs/ADR-021-notifications-as-a-bounded-context.md)).
  `Inbox` turns each kind into a sentence through a `Record` over the kinds it knows, so a kind added
  to the client's type cannot compile without its words, and a kind the server sends that this build
  does not know is shown as unknown rather than guessed at. Opening the inbox marks nothing read;
  the reader does.
- **The rules screen offers no default values.** Not a styling choice: a number suggested by the UI
  would be a working-time threshold chosen by a developer, which is exactly what this system
  refuses to contain.
- **The 422 body carries structured violations**, not formatted sentences, so a caller can resolve
  an employee to a name and group by person without parsing prose. The UI does exactly that.
- **`GET /api/constraints` returns thresholds, not just rule names.** `ConstraintSet.revisedTo`
  replaces the whole set rather than merging, so a caller that cannot read the current values cannot
  change one rule without deleting the others. The response mirrors the request shape for that
  reason.
- **A roster carries the period it covers**, so a caller resolves its assignments against the shifts
  that actually fall in it rather than whatever span it happens to be displaying.
- **Reading a roster requires Admin or Scheduler**, on the same reasoning as listing people: a
  roster says who works when.
- **An employee reads their own work through a separate, narrower port.** `ViewOwnScheduleUseCase`
  answers only for the personnel record the caller carries — the link `TenantActor` holds
  ([ADR-012](../ADRs/ADR-012-acting-identity-carries-its-employee-record.md)), never a `UserId`
  compared with an `EmployeeId` — and only from **published** rosters, because a draft binds nobody
  and a superseded one is no longer authoritative. It exists so that R12 does not have to be relaxed
  to satisfy F5. An actor with no personnel record is refused rather than shown an empty week: "you
  have no record" and "you are rostered for nothing" are different answers.
- **The acting identity comes from the access token and from nowhere else.** `X-User`, `X-Role`,
  `X-Organisation` and `X-Employee` are gone: no controller reads them, no filter checks them, and
  a request that sends them anyway is answered as whoever its token says it is. Each web adapter
  has one `actingIn(actor)` helper over `Actor.requireTenantMembership()`, which is the only way an
  organisation enters a request
  ([ADR-011](../ADRs/ADR-011-issue-our-own-access-tokens.md), [ADR-031](../ADRs/ADR-031-take-the-organisation-from-the-account.md)).
- **Every constraint version is stored, and any of them can be read back.** The table is keyed
  `(organisation_id, version)`, so revising never discards what came before, and
  `GET /api/constraints/{version}` answers the rules a roster naming that version was judged
  against (Q9). **Version one is never stored:** reading the rules stores nothing, and the first
  revision writes version two. Every organisation starts bound by the empty set, so
  `SchedulingService.findConstraints` answers version one from `ConstraintSet.initial` when no row
  holds it, and `roster.validated_against_constraint_version` carries no foreign key
  ([ADR-038](../ADRs/ADR-038-read-a-constraint-version-back-by-its-number.md)).
- **Approving a swap publishes a replacement; it never edits.** `SwapService` builds the roster that
  would have been published had the swap been in it, and then calls
  `PlanRostersUseCase.publishApproving` - `publish`, the same door generation and manual assignment
  go through, told which request it settles. So the constraint check, the supersession and both
  audit entries are not merely also applied to swaps, they are the same code, and a swap cannot
  become a way round rules generation cannot get round
  ([ADR-022](../ADRs/ADR-022-approving-a-swap-publishes-a-replacement-roster.md)). A refused approval
  takes the replacement draft with it, because the use case is one transaction (ADR-023).
- **A publication lapses the swap requests waiting on what it supersedes (R33).** Before it records
  anything, `SchedulingService.publish` reads `findPendingNaming` for each roster it supersedes and
  saves each request `LAPSED`. Once the published roster is stored, it records `SWAP_LAPSED` for
  each and tells both parties - all in the publication's transaction. A swap approval stores its
  own request as approved before that read, so it is never lapsed with the others; every other
  request on that roster is. The swap
  upsert only ever changes a request still `REQUESTED`, so no decision is written over another.
  The same lookup keeps an assignment - roster, shift and person - to one pending request when one
  is raised (R32), and the partial unique index `swap_request_one_pending_per_assignment` refuses a
  writer that skipped the publication lock
  ([ADR-039](../ADRs/ADR-039-lapse-pending-swaps-in-the-publication-that-supersedes-their-roster.md)).
- **Whether a colleague may cover a swapped shift is decided at approval, not at request.** Deciding
  it means listing colleagues, which discloses their unavailability, and an Employee may not. So a
  request may name somebody who cannot cover the shift — because they lack a qualification, or
  because they are already unavailable — and the Scheduler answering it is where that is found out,
  through the same `AssignmentEligibility` the manual path uses. A colleague who already works a
  shift overlapping the one handed over is found out a step later, when the replacement is
  published.
- **Publishing supersedes, so exactly one roster is authoritative.** `publish` marks every
  published roster of that organisation whose period overlaps as `SUPERSEDED`. Overlap rather than
  equality decides it, because a partly-overlapping published roster would otherwise still govern
  the days it shares, and the old roster goes whole, not only for the days replaced (Q16). Which
  rosters go is `Roster.isSupersededBy`'s decision.
- **Roster history is a record of when each roster governed, and is never rewritten (R37).** A
  roster stores `published_at` and `superseded_at`, each written once, and no status: the status is
  read off the two. Superseding writes `superseded_at` into a row that otherwise stays as it was
  published, so a roster governed its period from the one moment until the other, and which roster
  governed a day at any past moment is that interval read back. `publish` reads its `Clock` once,
  after the lock, and `Roster.nextMomentOfRecord` turns the reading into the moment it supersedes and
  publishes at: to the microsecond, and always after everything the organisation already recorded,
  so a clock that has not moved cannot record a roster that never governed. `JdbcRosterRepository`
  guards each write so that nothing is stored over a published roster but its supersession; a write
  the guard stops is `RosterNotEditableException`. Rows stored before `V6` carry the moment that
  migration ran, because none earlier was recorded
  ([ADR-037](../ADRs/ADR-037-keep-roster-history-as-a-record-in-time.md)).
- **Two rosters governing the same days at the same moment cannot be stored.** The
  `roster_one_published_per_moment` exclusion constraint, which `V2` introduced and `V6` widened,
  ranges over the organisation, the roster's period and the moments it governed, for published and
  superseded rows. A writer that publishes without seeing a publication still in flight is refused
  by PostgreSQL rather than leaving two rosters authoritative, and so is one that would record the
  past as having had two. PostgreSQL reports that as an exclusion violation, or, when two such
  writers wait on each other, by aborting one as a deadlock victim. `JdbcRosterRepository` turns
  either into `RosterPublicationConflictException`, which is answered 409
  ([ADR-026](../ADRs/ADR-026-make-overlapping-published-rosters-unrepresentable.md),
  [ADR-037](../ADRs/ADR-037-keep-roster-history-as-a-record-in-time.md)).
- **Assigning by hand asks the same questions generation asks, and no more.** `assign` enforces the
  three rules that are about one assignment — the person belongs to the organisation (R5), holds
  what the shift demands (R4), and has not declared they cannot work when it runs (F6) — plus that
  the shift falls inside the roster's own period, which generation cannot violate because it only
  ever assigns shifts it found there, and that the person holds no other shift overlapping it (R3):
  on this draft, which generation cannot violate because it skips anybody already working then, or
  on a published roster that stays authoritative beside the draft (R35), which generation can,
  because it is given no other roster's work
  ([#130](https://github.com/NaimElijah/HrSched/issues/130)). It deliberately leaves the
  working-time rules to publication,
  exactly as generation does: those judge a whole schedule rather than a single assignment, so the
  honest place for them is the step that cannot be bypassed, and a hand-made roster breaking a daily
  limit is refused at publication just as a generated one is.
  The availability half of that was missing at first, and the asymmetry was the point: generation
  filters candidates through `RosterCandidate.couldCover`, which tests availability, so it refused to
  roster somebody through their declared absence while assigning by hand accepted it — and
  publication could not catch it afterwards, because unavailability is not a `WorkingTimeConstraint`.
  `AssignmentEligibility` is one helper for exactly this reason: a rule enforced on one of two paths
  is not enforced.
- **Work that cannot be checked refuses publication.** If an assignment names a shift the
  repository cannot return, publication fails with `RosterNotVerifiableException` rather than
  quietly omitting that work from the constraint check. Omission would publish hours nobody
  counted, which is the failure publication exists to prevent.
- **Nobody is published in two places at once, whatever the rules say.** Building each person's
  schedule for the constraint check refuses one holding two overlapping shifts with
  `OverlappingAssignmentException` (R3), before any constraint is evaluated. It cannot be left to
  the rules: minimum rest is the only one that notices an overlap, it is optional, and an empty
  constraint set checks nothing — the asymmetry unavailability once had. `assign` asks the same
  question of the one person being assigned, so the board is told as it happens; publication asks
  it of everybody, so a swap approval and a generated draft are held to it too. Both answer 422.
- **R3 looks across rosters, not within one (R35).** A roster carries every shift overlapping its
  period, so a shift straddling two periods can sit on both, and two rosters that only touch are
  both authoritative. Each person's work on the roster being published is compared with the
  `HeldWork` they hold on every other published roster that stays authoritative once it lands.
  `Roster.isSupersededBy` and `Roster.staysAuthoritativeBeside` split one read of the organisation's
  rosters into what this publication supersedes and what it is judged beside. The same assignment
  carried by both rosters is one place to be, not two, and two pieces of held work are never
  compared with each other. Publication checks under the lock (ADR-027); `assign` checks without
  it, as early word. Nothing in the schema backs this up the way `roster_one_published_per_moment`
  backs R11, because `roster_assignment` stores no times. Held work is checked for R3 only: the
  working-time rules still judge one roster at a time
  ([#131](https://github.com/NaimElijah/HrSched/issues/131)), and generation is given no other
  roster's work ([#130](https://github.com/NaimElijah/HrSched/issues/130))
  ([ADR-033](../ADRs/ADR-033-judge-r3-across-authoritative-rosters-under-the-publication-lock.md)).
- **Reading the people of an organisation is restricted to Admins and Schedulers**, because
  unavailability is personal data. Generation reaches the same data through the anti-corruption
  layer carrying the Scheduler's own identity, so the restriction holds there too. One person's
  own declarations are read the way they are declared instead: `findUnavailability` and
  `findPreferences` admit the person themself, decided by `TenantActor.isThemself`, or an Admin or
  Scheduler on their behalf (F6). That answers for one person at a time, so it widens nothing.
  Preferences reach the API only through that read: `RosterableEmployee` still carries none,
  because nothing in scheduling weighs them yet.
- **A declaration is withdrawn by what it says, because it has no identity** (F26). Unavailability
  is a `TimeRange` and a preference a `ShiftPreference` value inside the `Employee` aggregate, and
  their rows are keyed by an ordinal the repository renumbers on every save. So
  `DELETE /api/employees/{id}/unavailability` and `/preferences` name the declaration in the
  query: its exact span, and for a preference its strength too. `Employee.noLongerUnavailableFor`
  refuses a span that only overlaps one declared, and `noLongerPreferring` removes every identical
  statement, since R26 lets them repeat and they cannot be told apart. The row is deleted outright;
  only the audit line says a withdrawal happened. No roster is touched, because a withdrawal only
  widens who may be assigned and scheduling reads unavailability live through
  `WorkforceDirectoryAdapter`
  ([ADR-040](../ADRs/ADR-040-withdraw-a-declaration-by-what-it-says.md)).
- **Every use case is one transaction, and no use case says so.** `UseCaseConfiguration` wraps each
  service in a transactional proxy, because the architecture test forbids Spring inside the
  application layer and so `@Transactional` cannot go where a reader would look for it
  ([ADR-023](../ADRs/ADR-023-the-transaction-boundary-is-the-use-case.md)). A nested use case joins
  its caller's transaction, which is what makes approving a swap atomic.
- **Changes to one roster take turns on its row.** `assign` and `unassign` read the draft with
  `RosterRepository.findForUpdate`, which holds its row until the use case's transaction ends, so a
  second edit of the same draft waits, then reads the first one's change: both apply. `publish`
  reads its draft the same way, under the publication lock, so an edit landing between its read and
  its save waits for it rather than being stored over; an edit that waited on a publication finds
  the roster published, and is refused. There is no version column to reject the later of two
  changes, because every command is a change the server makes to what it reads
  ([ADR-041](../ADRs/ADR-041-make-concurrent-changes-take-turns-on-the-row.md)).
- **Publications within one organisation take turns.** `publish`, `approveSwap`, `rejectSwap` and
  `requestSwap` first call `RosterRepository.lockPublicationsOf`, a transaction-scoped PostgreSQL
  advisory lock per organisation, before reading anything they decide from. A second publication
  therefore waits, then reads what the first committed: it supersedes that roster as R11 says, a
  second swap decision finds its request no longer pending, and a request is never raised against
  a roster a publication in flight is superseding (ADR-039). The lock is
  a convention every writer has to follow; the `roster_one_published_per_moment` constraint is what
  holds for one that does not
  ([ADR-027](../ADRs/ADR-027-take-turns-publishing-within-an-organisation.md)).
- **Three reads were written for a hash map and have not been revisited.** `publish`, `assign`
  and `findOwnSchedule` call `RosterRepository.findByOrganisation`, which now loads every roster the
  tenant has ever had together with all of their assignments, to find the few that overlap one week
  or stay authoritative beside it; `violations()` fetches each assignment's shift one at a time, an
  N+1 inside the publish transaction, and the work held elsewhere for R35 is resolved the same way;
  and every owned collection is written one `INSERT` per row. All three were free against a map and
  are not against a database. Fixing the first two means new port methods that push the predicate
  into SQL, which is a change inward of the ports and so a decision of its own. The requirement that
  sizes it is now stated — interactive requests within 300 ms at the 95th percentile — and nothing
  measures it yet ([#34](https://github.com/NaimElijah/HrSched/issues/34)).
- **An audit entry cannot be rolled back.** The trail is a log stream (ADR-018) and a transaction
  that fails after a service has recorded a change leaves a record of something that did not
  happen. This is newly true because the transaction is, and it is Q20's problem to solve.
