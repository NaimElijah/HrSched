# ADR-042: Judge a roster by one check that publication and an advisory read share

- **Status:** Accepted
- **Date:** 2026-10-19

## Context

Publication was the only place a roster was judged as a whole, and it judged less than F3 promises
("refused if it breaks any rule"):

- **It judged:** the working-time rules, and one place at a time (R3, R35).
- **It did not judge:** qualifications (R4), declared unavailability (R18), or belonging to the
  organisation (R5).
  - Those were asked only when somebody was put on a shift, by hand or by a swap approval
    (`AssignmentEligibility`). Generation asked them through `RosterCandidate.couldCover`.
  - So a draft could reach publication carrying somebody who declared they cannot work after being
    assigned, and it was published. Once qualifications can be revoked (F24), the same will be true
    of a revoked one.
- **It stopped at the first overlap.** An overlap threw `OverlappingAssignmentException`, a 422
  titled "Assignment refused", while working-time breaches were listed. A Scheduler fixing a draft
  learned of one problem per attempt.

#205 asks for a read-only check of a draft, running exactly the checks publication runs. It would
serve the Rosters screen, which today learns a draft is illegal only when publishing fails, and five
proposals that must not offer anything illegal (#196, #199, #200, #201, #207). A copy of the checks
would drift from publication.

This amends two things ADR-033 states:

- a person's own shifts are now compared in every pair, not only as neighbours by start time;
- an overlap found at publication now arrives in the list of violations, rather than as a 422
  titled "Assignment refused".

ADR-033's rule — R3 judged across every authoritative roster, under the publication lock — stands.

The owner decided on 2026-10-19:

- one shared check collects every violation;
- publication refuses R4 and R18 breaches too.

## Decision

**One judgement, `SchedulingService.violationsOf`, lists every rule a roster breaks, and both
publication and the read call it.** It combines:

- **`Roster.violationsUnder(constraints, shiftPeriods, heldElsewhere)`.**
  - It lists every overlap (R3, R35), every pair and not only the first, and the working-time rules,
    judged one employee's schedule at a time.
  - A person whose own work overlaps has their working time left unjudged. A schedule nobody can
    work has no meaningful rest or daily total.
- **`AssignmentEligibility.breachesOn(roster, shifts, candidates)`.**
  - It checks R5, R4 and R18 for every assignment, against one read of the workforce directory.
  - It asks the same two `RosterCandidate` questions that assigning by hand and generation ask.

**Publication refuses when that list is not empty**, with `RosterNotCompliantException` carrying
every breach: a 422 titled "Roster breaks the rules".

- Overlaps found at publication are part of that list.
- Assigning by hand still refuses at the first breach (`OverlappingAssignmentException`,
  `AssigneeUnavailableException`, …). One is enough to refuse one assignment, and the board names it
  as it happens.

**A violation keeps its three fields, `(employee, constraint, detail)`.**

- The rules that hold whatever the constraint set says are named by constants on
  `ConstraintViolation`, and built by its factories.
- A detail names things by identifier only. An unavailability breach names the shift and never the
  span, because the times somebody cannot work say something about their health or household.

**The read is `PlanRostersUseCase.findAssessment`, served at `GET /api/rosters/{id}/assessment`.**
It returns the same `violationsOf` together with coverage: each shift in the draft's period that has
fewer people than it requires, and by how many.

- **Advisory.** It runs read-only, as a `find…` use case, and takes neither the publication lock nor
  a row lock. Publication still decides, under its lock.
- **Drafts only.** A published roster was judged under the rules version it records (Q9), so asking
  about one is a 409.
- **Who may ask:** a Scheduler or an Admin, the people who may read every roster and everybody's
  availability (F4, R12).
- **Coverage counts only the draft's own assignments.** Whether staffing a straddling shift on the
  adjacent roster counts towards it is still open (#130, #135).
- **Proposed changes are deferred.** Judging a draft with changes applied in memory costs nothing to
  add, but the request shape is #201's diff, and nothing needs it yet.

## Consequences

**Easier**

- **Publication refuses everything F3 says it refuses.** A draft carrying somebody who has since
  become unavailable, or unqualified, cannot be published.
- **A Scheduler is told every breach in one answer.**
- **The read cannot drift from publication.** There is one implementation, and a test pins that the
  two agree on the same roster.
- **The proposals waiting on #205** have a deterministic check to ask before offering anything.

**Harder**

- **Approving a swap is refused because of somebody else.** If anybody on the roster has since
  become unavailable or unqualified, the replacement would carry them, so the swap waits until a
  Scheduler reassigns that shift. This is the consequence the owner accepted. It is the same as when
  the rules have been tightened since publication.
- **The API changed.**
  - An overlap found at publication now arrives in `violations`, under "Roster breaks the rules",
    where it used to be a lone `detail` under "Assignment refused".
  - The title for a working-time breach changed as well.
- **Neither path has been measured (#143).**
  - Publication now reads every rosterable person while holding the publication lock.
  - The read runs after every edit.
  - Each distinct assigned shift is looked up once (#34).
- **The read is stale as soon as it returns.** It says what publication would have said a moment
  ago, not what it will say.
- **R4 at publication cannot yet be reached over HTTP.** That needs a qualification to be revoked
  (F24) or a shift to be changed (F27). Until then it is pinned against the fakes only.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **A copy of the checks for the read** | The two copies drift, and the read would then describe some publication other than the real one. |
| **The read takes the publication lock** | Every read would queue behind publications, and its answer would be stale the moment it returned anyway. |
| **The whole judgement in the domain** | R4 and R18 are asked of a `RosterCandidate`, a port type the domain cannot see. A domain copy of it would be a second model of a person. |
| **Publication unchanged, R4 and R18 reported only by the read** | Then the read is not "what publication runs", and publication keeps accepting what F3 says it refuses. |
| **Refusing at the first breach** | That means one problem per attempt, for the Scheduler who has to fix them all. |
| **Assessing published rosters too** | That contradicts Q9: a published roster was judged under the version it records. |
| **A POST carrying proposed changes now** | No caller needs it yet, and the shape belongs to #201's diff. |

**Revisit when** #143 measures publication or the read over the 300 ms target, or a solver (#28)
needs this judgement as a scoring constraint. Either may call for a form that judges a change rather
than a whole roster.
