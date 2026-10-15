# ADR-038: Read a constraint version back by its number, and answer version one from the domain

- **Status:** Accepted
- **Date:** 2026-10-15

## Context

Q9 was answered on 2026-08-30: **a published roster keeps the version that approved it, and changes
are never retroactive.** A roster records `validatedAgainstConstraintVersion`. Since ADR-004 every
revision of an organisation's constraint set is stored, keyed `(organisation_id, version)`. But the
port offered only `findCurrent`, so nobody could be shown what a roster had been judged against once
the rules were revised (#30).

Forces:

- **Any member may see the rules** (F21), the Employee included. **Only an Admin or a Scheduler may
  read a roster** (R12), because a roster names everybody's work.
- **Version one is never stored.**
  - Reading the rules stores nothing, and the first revision writes version two, so every
    organisation starts bound by an empty set that exists only as `ConstraintSet.initial`.
  - Rosters published before an organisation's first revision name version one.
  - That is why `roster.validated_against_constraint_version` carries no foreign key (V1).
- **Tenancy.** Every organisation's second version is called 2, so the number alone identifies
  nothing. A cross-tenant read must answer exactly what a version nobody wrote does: a 404 (ADR-009).
- **ADR-032 closes the security chain by default**, so any new route has to be named there.

## Decision

**We read a version back by its number, from a route of the rules' own, and answer version one from
the domain.**

- **`GET /api/constraints/{version}`** answers that version's rules and thresholds.
  - Behind it is `ConfigureConstraintsUseCase.findConstraints`, which any member may call (F21).
  - Behind that is `ConstraintSetRepository.findVersion`, scoped by organisation like every lookup.
  - Another organisation's version answers the same 404 problem detail as one nobody wrote.
- **The use case answers version one from `ConstraintSet.initial`** when no row holds it. It asks
  the store first and falls back only for the number the initial set carries, so a stored version
  one, if one is ever written, wins.
- **The security chain names `/api/constraints/**`** where it named the exact path. That is the
  shape every other tenant route family already has.

## Consequences

**Easier**

- **Q9 can be demonstrated.** Given a roster, its version reads back as the rules it passed, however
  the rules have changed since. A labour inspector, or anybody else asking, gets the answer that held
  at the time.
- **No migration.** Existing rosters naming version one resolve without anything being backfilled.
- **An Employee can see the rules a roster was judged against** without being able to read the
  roster, which F21 and R12 together require.

**Harder**

- **Version one exists in code, not in the database.** A query run directly against
  `constraint_set` cannot see it, and `roster.validated_against_constraint_version` still cannot
  carry a foreign key.
  - **Revisit** when anything other than this use case must resolve a roster's version, such as a
    report written in SQL or an export. Storing version one when an organisation is onboarded, plus
    a backfill, would then be the change.
- **A future route under `/api/constraints/`** — a jurisdiction preset (F32), for instance — no
  longer forces the second security edit ADR-032 asks of an unnamed path. The use case behind it
  still decides, as for every other route family.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **`GET /api/rosters/{id}/constraints`** | Ties the answer to the roster that needs it. Rejected because reading through a roster would make it Admin and Scheduler only (R12), while the rules themselves are any member's to see (F21). The roster already names its version, so a caller has the number. |
| **Embed the rules in `RosterResponse`** | One request instead of two. Rejected because every roster listing would carry a copy of rules that are the same for most rosters, and the version would still be unreadable on its own. |
| **Store version one at onboarding, and backfill it** | The database would then hold every version, and the roster column could carry a foreign key. Rejected for now because nothing outside the use case needs to resolve a version yet, and a backfill would invent rows for a past nobody recorded. Revisit on the trigger above. |
| **Report version one as missing** | Honest about storage. Rejected because it would tell an inspector that the rules approving a roster are unknown, when they were the empty set and are known exactly. |
