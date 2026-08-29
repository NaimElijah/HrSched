# Requirements — HrSched

**Status: partially specified.** Purpose, roles, tenancy, and the shape of the working-time
rules are settled (Q2, Q3, Q4 — answered 2026-08-30). Individual functional requirements are
still being written, and **none is implemented**: the only code that exists is a walking
skeleton that satisfies nothing here.

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

`TODO`: this table is derived from the role definitions confirmed in Q2 and is **not yet
confirmed line by line**. Treat each row as proposed until reviewed. Nothing here is
implemented.

| ID | Role | Requirement | Enforced by |
| --- | --- | --- | --- |
| F1 | Scheduler | Create a draft roster for a date range | *not implemented* |
| F2 | Scheduler | Assign an employee to a shift, and remove that assignment | *not implemented* |
| F3 | Scheduler | Publish a draft roster, making it visible to employees | *not implemented* |
| F4 | Scheduler | See the roster and availability of every employee in their organisation | *not implemented* |
| F5 | Employee | See their own published roster | *not implemented* |
| F6 | Employee | State when they are and are not available | *not implemented* |
| F7 | Employee | Request a swap of one of their assignments with another employee | *not implemented* |
| F8 | Admin | Add, amend, and deactivate employees | *not implemented* |
| F9 | Admin | Define the qualifications an employee holds and a shift requires | *not implemented* |
| F10 | Admin | Configure the organisation's working-time constraint set | *not implemented* |

**Not yet decided, and blocking:** who approves a swap requested under F7 (the other employee,
a Scheduler, or both), and whether an Admin may also act as a Scheduler. See
[Open questions](#open-questions).

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
override. This is a deliberate choice with a cost, recorded under
[Open questions](#open-questions): emergency cover that genuinely requires breaking a rule has
no path through the system today.

### Rules that hold regardless of configuration

| ID | Rule |
| --- | --- |
| R1 | A shift period must end strictly after it starts. *Enforced by `ShiftPeriod`, tested by `ShiftPeriodTest`.* |
| R2 | An employee must have a name that is not blank. *Enforced by `Employee`, tested by `EmployeeTest`.* |
| R3 | An employee may not hold two overlapping assignments |
| R4 | An employee may only be assigned to a shift they hold the required qualifications for |
| R5 | An employee may only be assigned within their own organisation |
| R6 | A published roster may not be edited in place; it is superseded by a new version |

`TODO`: R3–R6 are stated but not yet implemented, and the legal state transitions of a roster
(`draft → published → superseded`?) still need pinning down.

### Constraints a set may express

`TODO`: the vocabulary of constraints is the next thing to specify, and it determines the
model. Candidates, each needing confirmation: minimum rest between consecutive assignments;
maximum working hours per day, per week, and averaged over a reference period; minimum
uninterrupted weekly rest; limits specific to night work; maximum consecutive working days;
contracted-hours floors and ceilings per employee.

---

## Non-functional requirements

- **Authentication and authorisation** — three roles per organisation: **Employee** (own
  roster, own availability, own swap requests), **Scheduler** (build and publish rosters, see
  all employees in the organisation), **Admin** (manage employees, qualifications, and the
  constraint set; does **not** build rosters). Identity provider is `TODO`.
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
- **Compliance** — availability, absence, and qualification records are personal data across
  multiple client organisations. `TODO`: confirm the obligations, including which regime
  applies and whether data location is constrained.

---

## Non-goals

`TODO`. Candidates worth deciding on explicitly rather than drifting into: payroll and pay
calculation, time and attendance clocking, applicant tracking, and being the HR system of
record.

---

## Open questions

| # | Question | Owner | Blocks |
| --- | --- | --- | --- |
| Q5 | Where does this run — self-hosted or a cloud target? | NaimElijah | Architecture, deployment; now weightier because the product is multi-tenant |
| Q6 | Is roster generation manual, assisted, or automatic (solver)? | NaimElijah | Scope, and whether a constraint solver becomes a dependency |
| Q7 | Who creates and administers organisations themselves? Is there a platform operator above the tenant Admin? | NaimElijah | Whether a fourth, cross-tenant role exists — which would be the one role permitted to see across tenants |
| Q8 | Is a constraint set scoped to the organisation, or may it vary by site, contract, or individual? | NaimElijah | The constraint model, and F10 |
| Q9 | What happens to an already-published roster when its constraint set changes and it becomes non-compliant? | NaimElijah | Roster state transitions, R6 |
| Q10 | With no override, how is genuine emergency cover handled? | NaimElijah | Whether "reject outright" survives contact with operations |
| Q11 | Who approves a swap (F7) — the other employee, a Scheduler, or both? | NaimElijah | F7 |

**Resolved**

| # | Question | Answer | Date |
| --- | --- | --- | --- |
| Q1 | Language, runtime, framework | Java 21, Spring Boot, Maven — [ADR-001](../ADRs/ADR-001-java-spring-boot-maven.md) | 2026-08-29 |
| Q2 | Which roles use the system | Employee, Scheduler, Admin | 2026-08-30 |
| Q3 | Single organisation or multi-tenant | Multi-tenant: independent organisations, strict isolation | 2026-08-30 |
| Q4 | Whose working-time rules apply | None hardcoded. A configurable constraint set; jurisdictions are presets | 2026-08-30 |

Question numbers are never reused, so Q5 and Q6 keep the identifiers other documents already
reference.
