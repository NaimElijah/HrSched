# Requirements — HrSched

**Status: specified, partially built.** Every question that blocked modelling is answered. The
domain is modelled, and a React user interface offers every capability below — for the three roles
within an organisation and for the platform Operator — over a PostgreSQL that survives a restart.
Generation is greedy rather than optimised, and there is no authentication.

Nothing in the user interface *enforces* a requirement. The `Enforced by` column below names
backend classes and tests on purpose: a screen that appears to satisfy a rule is not evidence that
the rule holds.

This document states **what the system must do**. *How* it is built lives in
[architecture.md](../architecture/architecture.md); *why* a given solution was chosen lives
in the [ADRs](../ADRs/). Neither is restated here.

---

## Purpose and scope

HrSched is a **staff shift scheduling (rostering) system, sold as a product to multiple
organisations**. It exists to turn two inputs — a set of shifts that need covering, and a set
of employees with availability and eligibility constraints — into a published roster, and to
keep that roster correct as reality changes (sickness, swaps, demand shifts, late
cancellations).

The value is in the constraints, not the calendar: a roster that ignores rest periods,
qualifications, or contracted hours is worse than no roster, because it looks authoritative
while being wrong.

**Who it is for** — independent organisations, each running its own staff, shifts, and rules
on a shared deployment, with no visibility of one another. Within an organisation there are
three roles: **Employee**, **Scheduler**, and **Admin**.

**Deliberately out of scope** — see [Non-goals](#non-goals).

---

## Functional requirements

Each requirement is stated as something a user or caller can *do*, and names the test or
class that enforces it once one exists — so this table can be checked rather than trusted.

`TODO`: this table was derived from the confirmed role definitions rather than stated directly,
so each row is **proposed until reviewed line by line**. Rows naming a class and a test are
implemented; the rest are not.

| ID | Role | Requirement | Enforced by |
| --- | --- | --- | --- |
| F1 | Scheduler | Define the shifts that need covering, with headcount and qualifications | `SchedulingService`, `RosteringHttpTest` |
| F1a | Scheduler | See the shifts needing covering over a span | `SchedulingService`, `SchedulingServiceTest` |
| F2 | Scheduler | Assign an employee to a shift, and remove that assignment | `SchedulingService`, `SchedulingServiceTest`, `RosteringHttpTest`. *Drafts only: a published roster is superseded, never edited (R6).* |
| F3 | Scheduler | Publish a roster, after it passes the organisation rules | `SchedulingService`, `RosteringHttpTest` |
| F4 | Scheduler | See the roster and availability of every employee in their organisation | `EmployeeService`, `EmployeeServiceTest`, `EmployeeHttpTest`. *Availability, qualifications and contracted hours are on the listing; the roster half is served by F11a rather than repeated here.* |
| F5 | Employee | See their own published roster | `SchedulingService`, `SchedulingServiceTest`, `RosteringHttpTest`. *The assignments naming them, from published rosters only — the whole roster stays Admin and Scheduler (R12).* |
| F6 | Employee | Declare when they cannot work, and state soft preferences | `EmployeeService`, `EmployeeServiceTest` ([ADR-012](../ADRs/ADR-012-acting-identity-carries-its-employee-record.md)) |
| F7 | Employee | Request a swap of one of their assignments with another employee | `SwapService`, `SwapServiceTest`, `SwapHttpTest` ([ADR-022](../ADRs/ADR-022-approving-a-swap-publishes-a-replacement-roster.md)). *Modelled as a transfer rather than a two-way exchange — Q22.* |
| F8 | Admin | Add employees | `EmployeeService`, `EmployeeServiceTest`, `EmployeeHttpTest` |
| F9 | Admin | Define qualifications, and grant them to employees | `QualificationService`, `QualificationServiceTest`, `QualificationHttpTest`; granting: `EmployeeService`, `EmployeeServiceTest` |
| F10 | Admin | Configure the organisation's working-time constraint set | `SchedulingService`, `RosteringHttpTest` |
| F11 | Scheduler | Generate a draft roster covering a period, filling every shift it legally can | `SchedulingService`, `RosteringHttpTest` |
| F11a | Scheduler | See the rosters their organisation holds | `SchedulingService`, `RosteringHttpTest` |
| F12 | Scheduler | Publish a draft, which is refused if it breaks any rule | `SchedulingService`, `RosteringHttpTest` |
| F13 | Operator | Administer organisations across the platform | `OrganisationService`, `OrganisationServiceTest`, `OrganisationHttpTest` ([ADR-019](../ADRs/ADR-019-an-application-layer-for-the-access-context.md)). *Onboard, list and read; there is no rename and no closure. Onboarding creates the organisation's first Admin in the same transaction - the operator is refused everything inside an organisation ([ADR-030](../ADRs/ADR-030-confine-the-platform-operator-to-the-register.md)), so one onboarded without an Admin would have nobody able to add one. That Admin must change the password they were given before anything else (ADR-029).* |
| F14 | Everyone | Sign in with a sign-in name and password, stay signed in across a reload, sign out, and change their own password | `SessionService`, `SessionServiceTest`, `AuthController`, `AuthHttpTest`, `SessionRefreshHttpTest`, `SessionHttpTest`; token verification: `AccessTokenDecoder`, `AccessTokenDecoderTest`, `ActorAuthenticationConverter`, `ActorAuthenticationConverterTest`, `SecurityConfiguration` ([ADR-011](../ADRs/ADR-011-issue-our-own-access-tokens.md), [ADR-028](../ADRs/ADR-028-keep-the-session-in-memory-and-a-rotating-refresh-cookie.md)). *A password somebody else chose must be changed before anything else; an account is never self-created (ADR-029).* |
| F15 | Admin | Administer the accounts of their own organisation: create one, list them, change what an account is, disable and restore one, and reset somebody's password | `AccountService`, `AccountAdministrationTest`, `AccountController`, `AccountHttpTest` ([ADR-029](../ADRs/ADR-029-provision-accounts-from-the-top-down.md), [ADR-030](../ADRs/ADR-030-confine-the-platform-operator-to-the-register.md)). *The organisation is taken from the token, never from the request, so there is no identifier for an Admin to change.* |

**Swaps (F7)** are approved by a **Scheduler**; the other employee is informed rather than asked,
and being informed means a notification they can go and read. Approving does not edit the published
roster — R6 forbids that — it publishes a replacement that supersedes it, through the same
publication every other roster goes through, so a swap breaking a working-time rule is refused with
no override. Whether the colleague may actually cover the shift is decided when a Scheduler answers
rather than when an Employee asks, because deciding it means reading colleagues' unavailability.

**Roster generation is automatic** — that is the point of the product. A Scheduler states what
needs covering and the system produces a legal roster. Today it does so greedily and optimises
nothing; see [ADR-005](../ADRs/ADR-005-roster-generation-behind-a-port.md).

---

## Business rules and invariants

### Working-time rules are configuration, not code

**The domain enforces a configured set of constraints. It does not encode any country's law.**
A jurisdiction — Israel, the EU directive, the UK regulations, a US state — is expressed as a
*preset*: a named constraint set with particular values. A constraint set belonging to no
jurisdiction at all is equally valid, and an organisation may define its own.

This has three consequences that the model must honour:

- **No legal threshold is hardcoded anywhere.** A minimum rest period is a value in a
  constraint set, never a constant in a domain class.
- **Each organisation configures its own set** (F10), because tenants may operate under
  different law or under stricter internal policy.
- **A preset is data, and its values must be sourced.** Before any preset ships, its numbers
  are taken from the statute and the source cited. A preset that is wrong produces rosters
  that are confidently illegal, which is the exact failure this product exists to prevent.

### Constraints are invariants, not warnings

A roster that violates its organisation's constraint set **cannot be published**. There is no
override, and none is needed: emergency cover means publishing a replacement roster that is
itself legal, or leaving the shift uncovered so the business sees the real problem. That is why
a published roster is superseded rather than edited (R6).

### Rules that hold regardless of configuration

| ID | Rule |
| --- | --- |
| R1 | A time range must end strictly after it starts. *`TimeRange`, `TimeRangeTest`* |
| R2 | An employee must have a name that is not blank. *`Employee`, `EmployeeTest`* |
| R3 | An employee may not hold two overlapping assignments. *`Roster`, `RosterTest`; on the manual path `SchedulingService`, `SchedulingServiceTest`, `RosteringHttpTest`; at swap approval `SwapServiceTest`; in generation `GreedyRosterGeneratorTest`* |
| R4 | An employee may only cover a shift whose qualifications they all hold. *`RosterCandidate`, `RosterCandidateTest`; on the manual path `SchedulingService`, `SchedulingServiceTest`* |
| R5 | An employee may only be assigned within their own organisation. *`SchedulingService`, `SchedulingServiceTest`, `RosteringHttpTest`* |
| R6 | A published roster may not be edited; it is superseded by a new version. *`Roster`, `SchedulingService`, `SchedulingServiceTest`* |
| R7 | An employee may not declare overlapping unavailability. *`Employee`, `EmployeeTest`* |
| R8 | A shift must require at least one person. *`Shift`, `ShiftTest`* |
| R9 | A shift may only require qualifications its own organisation has defined. *`SchedulingService`, `SchedulingServiceTest`* |
| R10 | A roster assigning a shift that cannot be resolved cannot be published. *`Roster`, `SchedulingServiceTest`* |
| R11 | Publishing a roster supersedes every published roster of that organisation whose period it overlaps. *`SchedulingService`, `SchedulingServiceTest`; against a publication in flight at the same time, `RosterRepository.lockPublicationsOf` (`SchedulingServiceTest`, `SwapServiceTest`, `JdbcRosterRepositoryTest`) and, behind it, the `roster_one_published_per_moment` constraint (`JdbcRosterRepository`, `JdbcRosterRepositoryTest`)* |
| R12 | A roster may only be read by an Admin or a Scheduler, because it is personal data about every colleague. *`SchedulingService`, `RosteringHttpTest`* |
| R13 | A sign-in name identifies one account across the whole platform, so two accounts can never hold the same one. *`AccountRepository`, `JdbcAccountRepositoryTest`, `AccountAdministrationTest`, `AccountHttpTest`* |
| R14 | An organisation always keeps at least one active Admin: the last one can be neither demoted nor disabled. *`AccountService`, `AccountAdministrationTest`, `AccountHttpTest`* |
| R15 | An account may be linked only to an employee of its own organisation, and an employee has at most one account. *`AccountService`, `AccountAdministrationTest`, `AccountHttpTest`; the second half also `JdbcAccountRepositoryTest`* |
| R16 | A password chosen by anybody other than the account's owner must be replaced before that account holds a role. *`Account`, `SessionService`, `ActorAuthentication`, `SessionServiceTest`, `AccountHttpTest`* |
| R17 | Disabling an account, and resetting its password, end every session it holds. A reset also clears any lockout. *`AccountService`, `AccountAdministrationTest`, `AccountHttpTest`* |

A roster moves `DRAFT → PUBLISHED → SUPERSEDED`, and no other transition is legal. Exactly one
roster is authoritative for any given moment, which is what R11 enforces.

**Only a Scheduler builds rosters.** An Admin manages people, qualifications and the constraint
set and cannot define shifts, generate or publish — F1/F3/F11/F12 against F8/F9/F10.

**Unavailability and rosters are personal data.** Listing the people in an organisation, and
reading any roster, are both restricted to Admins and Schedulers; an Employee cannot read a
colleague's. F5 — an employee seeing *their own* work — is a narrower capability beside that one
rather than a widening of it: it answers only for the personnel record the caller carries, so it
discloses nothing about anybody else. An actor with no personnel record is refused rather than
shown an empty week.

**Revising the rules replaces the whole set.** There is no partial update: a caller sends the rules
that should apply from now on, and any rule omitted is switched off. `GET /api/constraints`
therefore returns the thresholds, not only the rule names, so a caller changing one rule can resend
the rest instead of silently deleting them.

### Constraints a set may express

Implemented: **minimum rest between assignments**, **maximum daily working time**, **maximum
working time across the roster period**, **maximum consecutive working days**. Each is optional;
an unset constraint is simply not enforced, and an empty set is a valid configuration.

`TODO`: minimum uninterrupted weekly rest; limits specific to night work; contracted-hours
floors and ceilings; and averaging a weekly maximum over a reference period longer than the
roster, which needs history the current model does not hold.

### What makes one legal roster better than another

The objectives a generator should optimise, **none of them modelled yet**: fair distribution of
unpopular shifts, respect for stated preferences, and minimal disruption when re-generating a
published roster. These are explicitly *not* constraints — see ADR-005 and ADR-010.

---

## Non-functional requirements

- **Authentication and authorisation** — three roles per organisation: **Employee**,
  **Scheduler**, **Admin**, plus a platform **Operator** belonging to no organisation. The
  Operator administers the **register** of organisations across tenants — onboarding, listing and
  reading them (F13) — and is **refused inside any organisation**
  ([ADR-030](../ADRs/ADR-030-confine-the-operator-to-the-register-of-organisations.md)), enforced by
  `Actor.requireAccessTo`, every application service, and their tests. Authorisation is enforced in
  the application services. Authentication is being introduced now (F14, ADR-011): sign-in issues a
  signed access token and the acting identity is resolved from a stored account and session on every
  request. **The header placeholder still exists** on every route but the session endpoints, and any
  caller can still claim any role through it until it is deleted.
- **Tenant isolation** — the deployment is shared by independent organisations that must never
  see one another's data. Every aggregate belongs to exactly one organisation, and every query
  is scoped to it. A cross-tenant read is a security incident, not a bug, and isolation must be
  covered by its own tests rather than assumed. An organisation must also *exist*: a request naming
  one nobody onboarded is refused at the boundary with the same 404 an unknown record produces, so
  a tenant can no longer be conjured by inventing an identifier.
  *Enforced by `KnownOrganisationFilter`, `KnownOrganisationHttpTest`;
  [ADR-020](../ADRs/ADR-020-refuse-a-request-naming-an-unknown-organisation.md).*
- **Persistence guarantees** — every accepted write is durable: once a request answers 2xx the
  change has committed and survives a crash or a restart. **A use case is one transaction**, so a
  request refused part-way leaves nothing behind — including the replacement roster a refused swap
  approval used to strand. Every aggregate lives in PostgreSQL, and the schema is changed only by a
  versioned migration. Roster history is kept but is **not** append-only: superseding a roster
  changes its status in place — Q24. Nothing is purged and no retention period is set — Q23.
  *Enforced by `Jdbc*Repository` (×8) and their adapter tests, `TransactionalUseCases`,
  `SwapHttpTest`; [ADR-004](../ADRs/ADR-004-postgresql-behind-the-driven-ports.md),
  [ADR-023](../ADRs/ADR-023-the-transaction-boundary-is-the-use-case.md).*
- **Performance and scale** — `TODO`: state as measurable numbers per organisation (employees,
  shifts per planning period, concurrent schedulers), plus expected number of organisations.
- **Availability** — `TODO`: is this business-hours tooling, or must it be up when a 04:00
  shift starts? Selling it to third parties raises the bar.
- **Deployment** — containerised with externalised configuration; the provider is deliberately
  deferred to its own ADR so the choice does not leak into the design.
- **Observability** — every request is traced, and the trace id appears in every log line it
  produces and on the HTTP response, so a report from a person can be joined to what the process
  recorded. Logs are structured and machine-readable on stdout. Liveness and readiness are exposed
  as probes. Rate, latency, error rate, saturation and the product's own coverage measure are
  published for a scraper to pull. Instrumentation is vendor-neutral (OpenTelemetry), and nothing is
  exported until there is somewhere to export to — Q19.
  *Enforced by `TraceIdResponseFilter`, `TraceIdHttpTest`, `MetricsHttpTest`;
  [ADR-016](../ADRs/ADR-016-structured-logs-and-a-propagated-trace-id.md),
  [ADR-017](../ADRs/ADR-017-expose-metrics-to-be-scraped.md).*
- **Auditability** — every state change records who made it, in which organisation, and the
  identifier of what changed; every refusal, including a cross-tenant attempt, is recorded too. The
  record carries identifiers only and never a name, so the trail says who to ask rather than the
  answer. `TODO`: whether that trail must be durable, readable by an Admin, and for how long it is
  retained — Q20. ADR-004 no longer blocks it: there is now somewhere durable to put a trail, and
  what stays open is whether one should exist and under what obligations.
  *Enforced by `SchedulingService`, `EmployeeService`, `QualificationService` and their tests;
  `SchedulingAuditLogTest`, `WorkforceAuditLogTest`, `SchedulingExceptionHandlerTest`,
  `WorkforceExceptionHandlerTest`;
  [ADR-018](../ADRs/ADR-018-audit-through-a-port-not-a-queryable-trail.md).*
- **Compliance** — availability, absence, and qualification records are personal data across
  multiple client organisations. `TODO`: confirm the obligations, including which regime
  applies and whether data location is constrained.

---

## Non-goals

Deliberately not written yet, at the repository owner's direction. Candidates worth deciding on
explicitly rather than drifting into: payroll and pay calculation, time and attendance clocking,
applicant tracking, and being the HR system of record.

---

## Open questions

| # | Question | Owner | Blocks |
| --- | --- | --- | --- |
| Q12 | What identity provider, and how does an Actor get built from a real credential? | NaimElijah | Deleting the header placeholder; ADR-011 |
| Q13 | How are the generator objectives weighted against one another? | NaimElijah | ADR-010, and introducing a solver |
| Q14 | Over what reference period is a weekly maximum averaged? | NaimElijah | Whether constraint checking needs history beyond the roster |
| Q15 | What are the non-goals? | NaimElijah | Deferred deliberately |
| Q16 | When a new roster only partly overlaps a published one, should the old one be superseded whole, or only for the days replaced? | NaimElijah | Refining R11; today the whole roster is superseded |
| Q17 | Which time zone are working-time rules evaluated in? | NaimElijah | Daily and consecutive-day limits currently count days in UTC |
| Q19 | Which observability backend receives the OpenTelemetry export, and at what sampling rate? | NaimElijah | Turning OTLP export on; sampling below 1.0 |
| Q20 | Should an Admin be able to read their organisation's audit trail, and under what retention and erasure obligations? | NaimElijah | An `audit` bounded context; ADR-018's revisit trigger; part of the Compliance TODO |
| Q21 | By what channel is a colleague actually informed, and how quickly must that happen? | NaimElijah | Anything beyond the in-app inbox: email, push, an unread badge. Today somebody is informed when they next open the application, which for a rota change may be too late ([ADR-021](../ADRs/ADR-021-notifications-as-a-bounded-context.md)) |
| Q22 | Is a swap a transfer of one shift, or a two-way exchange of two? | NaimElijah | Whether `SwapRequest` gains a second shift; today it is a transfer ([ADR-022](../ADRs/ADR-022-approving-a-swap-publishes-a-replacement-roster.md)) |
| Q23 | How long is each kind of record kept, and what is erased on request? | NaimElijah | A retention policy and a purge; part of the Compliance TODO, and it constrains Q20 |
| Q24 | Must roster history be append-only, so that what was authoritative at a past moment can be reconstructed? | NaimElijah | Whether superseding writes a new row rather than changing a status ([ADR-004](../ADRs/ADR-004-postgresql-behind-the-driven-ports.md)) |
| Q25 | An employee may not list colleagues, so the user interface has them name the colleague taking a shift by pasting that colleague's employee identifier. Is a names-only colleague directory required, and may an employee see a colleague's name at all? | NaimElijah | Choosing a swap counterparty by name; what an employee may learn about colleagues, on the reasoning behind R12 |
| Q26 | A shift can straddle two roster periods, and both rosters can then carry it without either superseding the other. Which roster governs such a shift, and is R3 judged across every authoritative roster or within one? | NaimElijah | R3 across rosters: today it is enforced within one roster only ([#63](https://github.com/NaimElijah/HrSched/issues/63)) |
| Q27 | Are the security defaults right? Access token 10 min, session 12 h absolute and 1 h idle, lockout after 10 consecutive failures for 15 min, passwords at least 15 characters and at most 72 bytes, bcrypt strength 12. | NaimElijah | Whether `application.properties` changes; the length floor cites NIST SP 800-63B-4 3.1.1.2 and the lockout ceiling 3.2.2 |
| Q28 | How does a tenant recover when its only Admin is locked out or has lost their password? The platform operator is confined to the register and cannot help (ADR-030), and R14 only guarantees the account still exists. | NaimElijah | Any recovery path at all: a second Admin by policy, an operator-initiated reset that breaks ADR-030, or an out-of-band process |
| Q29 | Must a sign-in name be an email address? Today it is any string up to 254 characters, normalised and unique platform-wide. | NaimElijah | Validation in `SignInName`; whether anything may ever be sent to it (Q21) |
| Q30 | May the platform have more than one operator, and how is an operator's credential rotated? Today configuration creates exactly one, once, and never overwrites it (ADR-029). | NaimElijah | Whether operator accounts get an administration surface of their own |
| Q31 | Is multi-factor authentication required, and for which roles? | NaimElijah | A second factor in `SignInUseCase`, and what a recovery code would be |
| Q32 | Must an account holding the EMPLOYEE role always be linked to an employee? Today the link is optional for every role, so an EMPLOYEE account with none can sign in and see nothing of its own. | NaimElijah | Tightening R15; the self-service path in ADR-012 |

**Resolved**

| # | Question | Answer | Date |
| --- | --- | --- | --- |
| Q1 | Language, runtime, framework | Java 21, Spring Boot, Maven — [ADR-001](../ADRs/ADR-001-java-spring-boot-maven.md) | 2026-08-29 |
| Q2 | Which roles use the system | Employee, Scheduler, Admin | 2026-08-30 |
| Q3 | Single organisation or multi-tenant | Multi-tenant: independent organisations, strict isolation | 2026-08-30 |
| Q4 | Whose working-time rules apply | None hardcoded. A configurable constraint set; jurisdictions are presets | 2026-08-30 |
| Q5 | Where it runs | Containerised, provider deferred to its own ADR | 2026-08-30 |
| Q6 | How rosters are built | Automatic generation is the point — [ADR-005](../ADRs/ADR-005-roster-generation-behind-a-port.md) | 2026-08-30 |
| Q7 | Who administers organisations | A platform Operator, the only identity crossing tenants | 2026-08-30 |
| Q9 | A published roster when rules change | It keeps the version that approved it; changes are never retroactive | 2026-08-30 |
| Q10 | Emergency cover without an override | Supersede with a new compliant roster | 2026-08-30 |
| Q8 | What a constraint set is attached to | One per organisation; per-person variation is contract terms, held separately | 2026-08-30 |
| Q11 | Who approves a swap | A Scheduler; the colleague is informed | 2026-08-30 |
| Q18 | Whether an employee sees their own roster, and what "their own" means | Yes: the assignments naming them, from published rosters only. A narrower capability beside R12, not a widening of it | 2026-09-01 |

Question numbers are never reused. The answers above raised Q12 to Q15; modelling raised Q16 and Q17; building the UI raised Q18; instrumenting the backend raised Q19 and Q20; building the notification inbox raised Q21; building swaps raised Q22; giving the system a database raised Q23 and Q24; and putting an employee's side of swaps into the user interface raised Q25.
