# ADR-010: Score a roster by three objectives, weighted by each organisation

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

A roster is legal or it is not, and nothing in the system prefers one legal roster over another.
`requirements.md` names what makes a roster good: **a fair share of unpopular shifts, respect for
stated preferences, and as little disruption as possible when a published roster is replaced.**
None of these is modelled, so a solver has nothing to optimise (ADR-005, #28).

Forces:

- **Q13 was answered on 2026-09-22.** Each organisation weights the objectives itself, as
  configuration, as it configures its own constraint set. **A weight is never a constant**, so the
  rule that no statute number is written down extends to weights: nobody but the organisation picks
  one.
- **Objectives are not constraints.** A constraint is hard and has no override (ADR-005). An
  objective only ranks rosters that are already legal. Mixing the two would let a weight look like a
  legal threshold, or a threshold be traded away for a better score.
- **Constraints are versioned and objectives need not be.** A published roster keeps the
  constraint version that approved it, because legality is judged once and must stay true (Q9).
  Nothing is ever judged against a weight.
- **The owner decided four points on 2026-10-20 (#27):**
  - **The Scheduler marks a shift as unpopular** when defining it. Nothing infers it from the clock
    or from preferences. #208 already assumed unpopular shifts are "as the organisation configures
    them".
  - **Fairness within one roster is the lowest maximum burden.** That is the largest number of
    unpopular shifts any one person carries.
  - **An Admin sets the weights**, as for the constraint set (F10). Any member may read them, as F21
    reads the rules.
  - **The score is shown on the draft check (F34).** It is not kept anywhere.
- **What the backlog builds on this:**
  - #28 needs objectives a CP-SAT or MIP solver can optimise, so each measure must be expressible
    linearly in the assignments.
  - #198 fits the weights from Schedulers' edits, which needs the score to be linear in its
    weights.
  - #208 reports fairness over time, and must report the same fairness this record defines.
- **What scheduling knows today:**
  - **Stated preferences.** An employee states a span they would like to work (`PREFERS`) or would
    rather not (`PREFERS_NOT`) (F6). These live in workforce and never crossed into scheduling.
  - **The baseline for disruption.** A published roster a draft would supersede is found by
    `Roster.isSupersededBy`, the same rule publication uses (R11). Supersession is whole (Q16).

## Decision

**We score a roster by three measures, each a whole number where lower is better. An organisation
weights them, and the score is the weighted sum.**

### The measures

**Fairness: the heaviest unpopular load.**

- It is the largest number of unpopular shifts that any one person is assigned on the roster.
- It is zero when no shift is marked unpopular.
- It counts shifts, not hours.
- It looks at one roster at a time. Fairness over time is #208's to measure.

**Preferences: the preferences gone against.** Each of these counts once:

- an assignment whose shift overlaps a span its assignee would rather not work;
- a `PREFERS` statement that overlaps the roster's period, where its author holds no assignment
  overlapping it.

A span somebody would rather not work counts once for each assignment of theirs inside it. A
`PREFERS` statement counts once however many shifts it touches. So filling a shift beyond its
headcount can lower this measure by at most one for each `PREFERS` statement it meets.

**Disruption: the assignments changed.** This compares the draft with the published rosters it
would supersede. Each of these counts once:

- an assignment on those rosters that the draft does not keep. That includes assignments outside
  the draft's own period, because supersession is whole and those assignments are dropped too.
- an assignment the draft makes that those rosters did not, on a shift overlapping one of their
  periods.

A draft superseding nothing scores zero here. Nobody was told anything it could change.

### The weights

- **A weight is a whole number of at least one**, set per organisation and per objective (R40).
- **An objective with no weight is left out of the score**, as an unset working-time rule is not
  enforced. An organisation that weights nothing scores every roster zero, which is how rosters are
  judged today.
- **A zero is refused** rather than read as "off", so there is one way to say it.
- **The score is the sum of weight times measure** over the weighted objectives. It is linear in
  the weights, and each measure is linear in the assignments, given the usual auxiliary variable
  for a maximum.
- **The weights are one record per organisation, replaced whole and not versioned.**
  - An Admin revises them through `PUT /api/objectives`, and any member reads them through
    `GET /api/objectives`.
  - A revision replaces every weight at once, as a constraint revision does, so two Admins
    revising at once cannot lose half of each other's change.

### Where the score is used

- **The draft check (F34) reports each measure, its weight and the total**, beside the violations
  and shortfalls. It judges a draft whether or not the draft is legal, and a good score never makes
  an illegal draft publishable.
- **Generation is unchanged.** The greedy generator still optimises nothing (ADR-005). The solver
  that optimises these objectives is #28.

## Consequences

**Easier**

- **A solver has something to optimise** that the organisation, not a developer, has weighted.
- **Two rosters are compared by one number under one weighting.** A Scheduler sees on the draft
  check whether an edit made the draft better or worse.
- **Each measure is a count a person can check by hand**, such as "nobody carries more than three
  unpopular shifts".
- **#208 and #198 inherit a fixed definition.** They measure and fit the very terms a solver will
  optimise.

**Harder**

- **The measures are raw counts, not normalised.** One measure can dwarf another on a large roster,
  and a weight only partly corrects that. #198 normalises in its own fit.
  - **Revisit** when an organisation cannot find weights that balance the objectives, or when
    #198's fit needs normalised terms in the score itself.
- **Fairness counts shifts, not hours.** Two unpopular shifts of very different lengths weigh the
  same.
  - **Revisit** when an organisation's shift lengths vary widely enough that counting misleads.
- **Marking is manual.** A Scheduler marks each unpopular shift as it is defined. Shifts cannot yet
  be changed (F27, #89), so a mark set wrongly stays until then.
  - **Revisit** when recurring shifts make marking a burden, or when night work gets a configured
    definition (#102).
- **Nothing records the weights a roster was scored or generated under.**
  - **Revisit** when a roster must carry them: training data (#190), or fitting weights from edits
    (#198).
- **A `PREFERS` statement no shift could ever meet still counts.** It is constant across every
  roster for the same shifts, so it never changes which roster is better. It does inflate the total
  shown.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Objectives inside `ConstraintSet`** | One configuration screen. Rejected because constraints are hard and versioned, and a weight beside a threshold invites reading one as the other. |
| **A priority order instead of weights** | Easy to explain: fairness first, then preferences. Rejected because Q13 chose weights, and a strict order gives no way to trade a little of one objective for a lot of another. |
| **Normalise each measure into a share** | Weights would be comparable across objectives and rosters. Rejected for now because a share is harder to check by hand than a count, and #198 can normalise in its own fit. Revisit on the trigger above. |
| **Fairness as the gap between most and least burdened** | Rewards spreading at the bottom too. Rejected by the owner: one person unavailable all period pins the minimum at zero. |
| **Fairness as a sum of squares** | Rewards an even spread everywhere. Rejected by the owner: it is quadratic, so harder for a MIP solver and to explain. |
| **Unpopularity from configured time windows** | No per-shift marking. Rejected by the owner for now: it needs zone and partial-overlap rules that the per-shift mark avoids. Revisit on the marking trigger above. |
| **Unpopularity from stated preferences** | No new configuration. Rejected because it double-counts the preference objective, and #208 measures unpopularity as configured. |
| **Versioned weights, kept like the constraint set** | A roster could name the weights it was judged under. Rejected because nothing is judged against a weight, so there is nothing a version would keep true. Revisit on the trigger above. |
| **A weight of zero meaning "off"** | One fewer state. Rejected because zero and unset would then say the same thing two ways, and a threshold of zero is refused too (R25). |
