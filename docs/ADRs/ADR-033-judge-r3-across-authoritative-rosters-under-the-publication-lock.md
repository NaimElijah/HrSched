# ADR-033: Judge R3 across authoritative rosters in the application, under the publication lock

- **Status:** Accepted
- **Date:** 2026-10-10

## Context

R3 says an employee may not hold two overlapping assignments. Until now it was judged within one roster: `Roster.schedulesFor` compared one person's shifts on the roster being published and nothing else.

That was not enough. A roster carries every shift that *overlaps* its period (generation uses `ShiftRepository.findWithin`, and `assign` checks overlap too), so a shift running from Sunday night into Monday can sit on either week's roster. Two adjacent published rosters only touch, so R11 supersedes neither and `roster_one_published_per_moment` (ADR-026) allows both. Week 1 could put Ada on Sunday 22:00–Monday 06:00 and week 2 on Monday 00:00–08:00. Both were published and authoritative, and Ada was in two places at once. The owner decided on 2026-09-22 (Q26, R35): either roster may carry a straddling shift, and R3 is judged across every authoritative roster.

Forces:

- **R3 is a rule between aggregates now.** The evidence is other `Roster` aggregates, and the times are on `Shift`s, which the roster does not hold. Constraint checking already lives in the application layer for the same reason. The judgement stays the domain's.
- **Publication must see what it checks against.** Two publications racing could each pass a check against what the other had not yet committed.
- **"Authoritative" must mean once this roster lands.** Publication supersedes every published roster it overlaps, whole (R11, Q16). Work on those rosters stops binding anybody the moment this one is published, so it is no evidence against it.
- **Generation re-covers a straddling shift.** Generating next week picks up Sunday night again and, being deterministic, puts the same person on it. If that counted as an overlap, publication would refuse nearly every week after a night shift.
- **Never let unverifiable data pass a check**, but do not let one old unresolvable row block an organisation forever. Every past week stays `PUBLISHED`, because only overlap supersedes.

## Decision

**We judge R3 across rosters in the application layer, on evidence the service gathers, and the domain decides.**

- **`HeldWork`** (`scheduling.domain.model`) is work somebody already holds on another roster: the roster, the shift, the person, and when it runs. `Roster.requireNoOverlappingWorkFor` and `Roster.schedulesFor` take it alongside the roster's own shift periods.
- **One person's own shifts are compared with each other as before**, by sorted neighbours. **Each own shift is compared with every piece of their held work, pairwise.** The neighbour shortcut is unsound once some pairs are deliberately skipped. Two pieces of held work are never compared with each other: if they overlap, that breach belongs to the rosters carrying them, which may predate this rule.
- **The same assignment on two rosters is one assignment, not an overlap.** The same shift, held by the same person on another roster, is skipped. R35 lets either roster carry a straddling shift, and that is one place to be, not two.
- **`Roster.isSupersededBy(replacement)` and `Roster.staysAuthoritativeBeside(replacement)`** split the organisation's rosters. Both require a `PUBLISHED` roster of the same organisation that is not the replacement itself. Overlap decides which of the two a roster is. `publish` makes **one read** after taking the publication lock (ADR-027) and splits it with these two predicates. The first set is superseded; the second is resolved into `HeldWork`. The two can never disagree about a roster.
- **Only the people on the draft are resolved** (only the assignee, in `assign`), with each shift looked up once. A held shift of theirs that cannot be resolved refuses with `RosterNotVerifiableException`, naming the roster that carries it. A colleague's unresolvable shift is never read.
- **`assign` checks without the lock.** That is early word for the Scheduler at the board. **`publish` is the guarantee**, and swap approval reaches it because approving publishes a replacement (ADR-022). Both answer 422 "Assignment refused", and the detail names the other roster.
- **Held work is checked for R3 only.** It does not enter the schedules the working-time rules judge (#131).

## Consequences

**Easier**

- **Nobody is published in two places at once, on any path.** That covers assigning, publishing, and approving a swap, whichever roster each shift sits on.
- **Supersession and R35 share one definition of "authoritative".** It lives in the aggregate rather than in a service filter.
- **A Scheduler is told at the board** which other roster holds the clashing work.

**Harder**

- **There is no schema backstop.** R11 has `roster_one_published_per_moment` behind it; R35 has only the lock. A writer that publishes without `lockPublicationsOf` could race past it. ADR-027 already makes every publication through the use cases take turns, and this rule depends on that.
- **Publication reads every roster the organisation holds**, superseded history included, and resolves the draft's people's shifts on every authoritative roster, past weeks included. That runs inside the publication lock, so the cost grows with history, against the 300 ms 95th-percentile target `requirements.md` sets for interactive requests. Nothing measures that yet. It is the same in-memory read `findOwnSchedule` already makes, and #34 tracks reshaping those reads. **Revisit** when a publication or an assignment is measured above that target, or when #34 pushes the predicate into SQL.
- **Generation still sees one roster.** It can draft a roster that publication then refuses, for example by putting somebody on an early Monday overlapping a Sunday night that someone else holds on next week's draft. Fixing that needs the owner to decide whether a straddling shift staffed on one roster counts towards its headcount on the other (R22): #130.
- **A straddling shift may be staffed on both rosters by different people.** R22 allows more people than the headcount, so this is not a breach, but nothing reconciles the two either.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **An exclusion constraint, as ADR-026 did for R11** | `roster_assignment` stores no times. The constraint would need each shift's period copied onto every assignment row and kept in step with the shift. It would also need `status` from the roster, so a published roster's assignments would have to be denormalised to know they are binding. That is a second copy of the truth to protect one rule, while the lock already serialises every writer that matters. Revisit if a writer outside the use cases must publish, or if a second database engine must be supported. |
| **Count the same assignment on two rosters as an overlap** | It is the literal reading of "two assignments". But it would refuse generation's ordinary output at nearly every week boundary with a night shift. It also guards against nothing: one shift worked once is one place to be. |
| **Compare all of a person's work, own and held, as one sorted list** | Simpler, but it would compare held work with held work. Two old published rosters that overlap each other would then block every later publication, with nothing on the new draft to fix. |
| **Resolve every assignment on every authoritative roster** | It would make every publication pay for the whole organisation's history. One unresolvable shift on any old roster would also block the organisation for ever. Only the draft's people can clash with it. |
| **Check only at publication, not at assignment** | Correct, but the Scheduler would learn of the clash only when publishing the whole roster. `assign` already answers R3 within the draft for that reason. |
