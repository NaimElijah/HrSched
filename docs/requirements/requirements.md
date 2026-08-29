# Requirements — HrSched

**Status: skeleton.** The purpose below is settled. Everything from *Functional
requirements* onward is not, and is marked `TODO` rather than guessed at.

This document states **what the system must do**. *How* it is built lives in
[architecture.md](../architecture/architecture.md); *why* a given solution was chosen lives
in the [ADRs](../ADRs/). Neither is restated here.

---

## Purpose and scope

HrSched is a **staff shift scheduling (rostering) system**. It exists to turn two inputs —
a set of shifts that need covering, and a set of employees with availability and
eligibility constraints — into a published roster, and to keep that roster correct as
reality changes (sickness, swaps, demand shifts, late cancellations).

The value is in the constraints, not the calendar: a roster that ignores rest periods,
qualifications, or contracted hours is worse than no roster, because it looks authoritative
while being wrong.

**Who it is for** — `TODO`: which organisation type and size (single site vs. multi-site,
10 staff vs. 1,000), and which roles use it (scheduler, employee, manager, admin).

**Deliberately out of scope** — see [Non-goals](#non-goals), currently open.

---

## Functional requirements

Each requirement is stated as something a user or caller can *do*, and names the test or
class that enforces it once one exists — so this table can be checked rather than trusted.

| ID | Requirement | Enforced by |
| --- | --- | --- |
| `TODO` | `TODO` — e.g. *"A scheduler can publish a roster for a date range."* | *not implemented* |

`TODO`: enumerate the capabilities. Suggested order to work through — **roster
construction**, **availability capture**, **publication and notification**, **change
after publication** (swaps, cancellations, cover), **reporting**. Do not add a row until
the capability is actually wanted; a speculative row is worse than a missing one.

---

## Business rules and invariants

The constraints the domain enforces: limits, legal state transitions, uniqueness, validation.

`TODO`. These are the questions that decide the shape of the domain model — answer them
before any code is written, because each answer either is or is not representable in the
type system, and that is the difference between a bug and an impossible state:

- Can one employee hold two overlapping assignments? Never, or with an explicit override?
- What is the minimum rest period between two assignments, and is it a hard rule or a warning?
- Which states can a roster be in, and which transitions are legal
  (`draft → published → ?`)? Can a published roster be edited in place, or only superseded?
- Does a shift have a minimum and maximum headcount, and what happens when it is under-filled?
- Which qualifications gate which shifts, and who may grant one?
- Are contracted hours a hard cap, a soft target, or informational?

---

## Non-functional requirements

- **Authentication and authorisation** — `TODO`: identity source, and the role/permission
  model. At minimum: who may publish a roster, and who may see whose availability.
- **Persistence guarantees** — `TODO`: what must survive a crash, whether roster history is
  append-only or mutable, and the retention period.
- **Performance and scale** — `TODO`: expected employees, shifts per planning period, and
  concurrent schedulers. State targets as measurable numbers (e.g. *p99 under 200 ms for a
  roster read at 500 employees*), never as "fast".
- **Availability** — `TODO`: is this business-hours-only tooling, or does it need to be up
  when a 04:00 shift starts?
- **Compliance** — `TODO`: two live questions. (1) Rosters encode working-time regulation,
  which is jurisdiction-specific — which jurisdiction? (2) Availability, absence, and
  qualification records are personal data; confirm the obligations that follow.

---

## Non-goals

`TODO`. What the project deliberately does not do, so nobody re-proposes it. Candidates
worth deciding on explicitly rather than drifting into: payroll and pay calculation, time
and attendance clocking, applicant tracking, HR records of record.

---

## Open questions

| # | Question | Owner | Blocks |
| --- | --- | --- | --- |
| Q1 | Which language, runtime, and framework? | NaimElijah | ADR-001, and every command in the README |
| Q2 | Which roles use the system, and what may each do? | NaimElijah | Functional requirements, authorisation model |
| Q3 | Single organisation, or multi-tenant? | NaimElijah | Data model, ADR on storage strategy |
| Q4 | Which jurisdiction's working-time rules must a roster satisfy? | NaimElijah | Business rules, compliance |
| Q5 | Where does this run — local, self-hosted, or a cloud target? | NaimElijah | Architecture, deployment section of the README |
| Q6 | Is roster generation manual, assisted, or automatic (solver)? | NaimElijah | Scope, and whether a constraint solver is a dependency |
