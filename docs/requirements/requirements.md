# Requirements — HrSched

**Status: specified, partially built.** Every question that blocked modelling is answered. The
domain is modelled and F1, F3, F5 (read), F8, F9 and F10 work end to end; generation is greedy
rather than optimised, nothing survives a restart, and there is no authentication.

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
| F2 | Scheduler | Assign an employee to a shift, and remove that assignment | *not implemented* |
| F3 | Scheduler | Publish a roster, after it passes the organisation rules | `SchedulingService`, `RosteringHttpTest` |
| F4 | Scheduler | See the roster and availability of every employee in their organisation | *not implemented* |
| F5 | Employee | See their own published roster | *partially: rosters are readable, not yet filtered to self* |
| F6 | Employee | Declare when they cannot work, and state soft preferences | `EmployeeService`, `EmployeeServiceTest` |
| F7 | Employee | Request a swap of one of their assignments with another employee | *not implemented* |
| F8 | Admin | Add employees | `EmployeeService`, `EmployeeServiceTest` |
| F9 | Admin | Define qualifications, and grant them to employees | `QualificationService`, `EmployeeServiceTest` |
| F10 | Admin | Configure the organisation's working-time constraint set | `SchedulingService`, `RosteringHttpTest` |
| F11 | Scheduler | Generate a draft roster covering a period, filling every shift it legally can | `SchedulingService`, `RosteringHttpTest` |
| F12 | Scheduler | Publish a draft, which is refused if it breaks any rule | `SchedulingService`, `RosteringHttpTest` |
| F13 | Operator | Administer organisations across the platform | *not implemented* |

**Swaps (F7)** are approved by a **Scheduler**; the other employee is informed rather than
asked. Not yet implemented.

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
| R3 | An employee may not hold two overlapping assignments. *`GreedyRosterGenerator`, `MinimumRestBetweenAssignments`* |
| R4 | An employee may only cover a shift whose qualifications they all hold. *`RosterCandidate`, `EmployeeTest`* |
| R5 | An employee may only be assigned within their own organisation. *`SchedulingService`, `RosteringHttpTest`* |
| R6 | A published roster may not be edited; it is superseded by a new version. *`Roster`, `RosterTest`* |
| R7 | An employee may not declare overlapping unavailability. *`Employee`, `EmployeeTest`* |
| R8 | A shift must require at least one person. *`Shift`, `RosteringHttpTest`* |

A roster moves `DRAFT → PUBLISHED → SUPERSEDED`, and no other transition is legal.

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
  **Scheduler**, **Admin**, plus a platform **Operator** belonging to no organisation and the
  only identity permitted to cross the tenant boundary. Authorisation is enforced in the
  application services. **Authentication does not exist**: the acting identity currently comes
  from request headers and any caller can claim any role. The identity provider is `TODO`
  (ADR-011), and the placeholder must be deleted when it lands.
- **Tenant isolation** — the deployment is shared by independent organisations that must never
  see one another's data. Every aggregate belongs to exactly one organisation, and every query
  is scoped to it. A cross-tenant read is a security incident, not a bug, and isolation must be
  covered by its own tests rather than assumed.
- **Persistence guarantees** — `TODO`: what must survive a crash, whether roster history is
  append-only, and the retention period. Blocked on ADR-004.
- **Performance and scale** — `TODO`: state as measurable numbers per organisation (employees,
  shifts per planning period, concurrent schedulers), plus expected number of organisations.
- **Availability** — `TODO`: is this business-hours tooling, or must it be up when a 04:00
  shift starts? Selling it to third parties raises the bar.
- **Deployment** — containerised with externalised configuration; the provider is deliberately
  deferred to its own ADR so the choice does not leak into the design.
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

Question numbers are never reused. The answers above raised Q12 to Q15.
