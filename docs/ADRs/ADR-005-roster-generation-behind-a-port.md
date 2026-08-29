# ADR-005: Automatic roster generation, behind a port, with a greedy first implementation

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

Q6 is answered: **automatic generation is the point of the product**. A scheduler should state
what needs covering and get back a legal roster, not assign people by hand while the system
tuts at them.

That answer implies a constraint solver. It also implies a great deal more than a solver: an
objective model, weights to tune, and a modelling discipline that tends to dominate whatever
else is in the same change. Meanwhile the domain that a solver would need — shifts, candidates,
constraints — is the same domain that manual assignment and validation need.

## Decision

**Generation is a driven port**, `RosterGenerator`, taking the shifts to cover, the candidates
available, and the constraint set, and returning assignments.

**The implementation shipped today is greedy.** It walks shifts in chronological order and adds
the first candidate who is qualified, available, and whose resulting schedule still satisfies
every constraint. It optimises nothing.

**No solver dependency is added in this change.** Introducing one replaces a single adapter and
touches nothing inward of the port.

**An uncoverable shift is left short**, never filled illegally. A roster honest about a gap is
more useful than one that hides it, and it surfaces a real staffing problem to the business.

## Consequences

**Easier**

- Everything downstream of generation — publishing, constraint checking, the API, tenant
  isolation — is real, testable and finished, rather than waiting behind a solver.
- The port fixes the shape of the problem. Whatever solves it later receives exactly the three
  inputs the greedy version does.
- No heavyweight dependency was chosen while the domain was still moving.

**Harder**

- **The product does not yet do the thing it exists to do.** The greedy generator will produce
  poor rosters: it ignores fairness, preferences and disruption entirely — the three objectives
  chosen as what makes a roster good — and being greedy it will sometimes use someone up early
  and leave a later shift uncoverable. Every document that mentions generation says this
  plainly; the risk is that someone reads "generates rosters" and believes it.
- **Objectives are not modelled at all yet.** The constraint model deliberately holds only hard
  rules, so the concepts a solver would optimise still have to be designed.
- A greedy result is order-dependent, which makes it a poor baseline for judging a solver later.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Add Timefold now** | Delivers the actual product value soonest, and is where this is going. Rejected for this change only: it is a significant dependency needing its own ADR, and solver tuning reliably swamps review of everything beside it. **Revisit immediately** once the objectives are modelled — this is the next substantial piece of work, not a someday. |
| **Manual assignment with validation, no generation at all** | Smaller and honest about what works. Rejected because it would shape the API around a workflow the product does not intend to have. |
| **Generation as a domain service rather than a port** | Fewer indirections. Rejected because a solver is emphatically infrastructure — a library, a time budget, possibly a separate process — and putting it inside the hexagon would drag all of that in with it. |
