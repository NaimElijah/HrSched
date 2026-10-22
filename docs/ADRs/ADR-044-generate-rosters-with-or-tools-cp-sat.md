# ADR-044: Generate rosters with CP-SAT from Google OR-Tools

- **Status:** Accepted
- **Date:** 2026-10-22

## Context

ADR-005 put generation behind the `RosterGenerator` port, with a greedy placeholder, and said to
revisit it "immediately once the objectives are modelled". ADR-010 modelled them on 2026-10-20,
so the solver is next (#28). The owner chose CP-SAT on 2026-10-22, from the alternatives below.

Forces:

- **The rules are hard, and there is no override.** Nothing generation returns may break the
  constraint set, R3, R35, R4, R5 or R18. Since #28 the use case judges every generator's answer
  before storing it, so an illegal answer is a defect in the generator rather than a poorer roster.
- **The objectives are soft, and they come second.** F11 asks for a draft "filling every shift it
  legally can". ADR-010's three measures then rank the rosters that do. Each measure is linear in
  the assignments, and the score is linear in the weights.
- **Weights are unbounded.** R40 asks only that a weight be a whole number of at least one.
- **The stated scale** is 1,000 employees and 500 shifts in a period, generated in at most ten
  minutes (requirements.md, *Performance and scale*). Generation answers an HTTP request today.
- **What the backlog builds on the solver:**
  - #197 warm-starts it from a predicted roster.
  - #209 prunes its search, and must report the optimality gap that costs, against "the solver's
    bound where it gives one".
  - #199 extracts a minimal set of conflicting rules for each shift left short.
  - #198 fits weights from Schedulers' edits, which needs the score linear in them.
- **A new dependency needs a justification**: what it replaces, its maintenance, its licence, and
  why nothing already present will do. An exact optimiser for a 0-1 problem of this size is not
  something to write by hand, and nothing on the classpath is one.

## Decision

**We generate with CP-SAT, the constraint programming solver in Google OR-Tools
(`com.google.ortools:ortools-java` 9.12.4544), confined to `scheduling.adapter.out.generation`.**
It replaces `GreedyRosterGenerator` as the one generator wired in. Greedy moves to the tests, as the
baseline a benchmark measures CP-SAT against.

### The model

- **Java decides, on constant data, with the domain's own methods,** who could take which shift
  (`RosterCandidate.couldCover`, work held elsewhere) and how each shift's time falls on each day in
  its own zone. CP-SAT sees only a boolean for each eligible pair of person and shift, and exact
  integer sums over them.
- **Each kind of working-time rule is one case** of an exhaustive `switch` over the sealed
  `WorkingTimeConstraint`, with no default. A new kind (#101 to #103) does not compile until it is
  encoded.
- **Coverage first, then ADR-010's weighted score, in two solves.** The first maximises the places
  filled. The second holds coverage at least at the best the first found, starts from that
  solution, and minimises the weighted score. One weighted objective would need a coefficient on
  coverage larger than any score, and with unbounded weights that can overflow a 64-bit integer.
- **Each solve has a time limit, as configuration** (`hrsched.generation.time-limit-per-phase`).
  A first solve that finds nothing in time fails loudly. Otherwise the best roster found is
  returned, and the use case judges it before storing it.
- **The model is checked from both sides by property tests on seeded problems**: whatever the
  domain accepts the model accepts, whatever the model returns the domain accepts, and the
  solver's score equals `RosterScore` computed by the domain.

### The version

**9.12.4544 (2025-02), not the newest, 9.15.6755 (2026-01).** From 9.13 on, OR-Tools' Windows
libraries need the Visual C++ runtime 14.40 or later. Oracle JDK 21 bundles 14.36 in its `bin`
directory, and Windows resolves a library's dependencies from the application's directory before
`System32`, so on a developer's Windows machine the JVM crashes in `msvcp140.dll` on the first
solve. Tried on that machine:

- 9.13.4784, 9.14.6206 and 9.15.6755 crash the JVM.
- 9.12.4544 and 9.11.4210 pass every solver test.
- 9.15.6755 passes every solver test on the same JDK with only its bundled runtime replaced by
  14.50, so the runtime is the cause.

Linux, where CI and every deployment run, is unaffected. Dependabot ignores 9.13 and later.
**Revisit** when the JDK developers run on Windows bundles runtime 14.40 or later, or none.

### Licence and maintenance

- **OR-Tools** is Apache-2.0 and maintained by Google. Releases 9.12 (2025-02), 9.14 (2025-06) and
  9.15 (2026-01) are all on Maven Central.
- **It brings `jna-platform`**, dual LGPL-2.1 or Apache-2.0. JNA is pinned to the version
  Testcontainers' Docker client already needs, so the build's Docker transport is not downgraded.
- **It brings `protobuf-java`** (BSD-3-Clause). The Boot BOM already manages it, so nothing new is
  resolved.

## Consequences

**Easier**

- **A generated roster is legal by construction.** A rule is a constraint the solver cannot break,
  not a penalty its search has to drive to zero in time. The use case's check is a backstop for a
  modelling defect, not the thing legality rests on.
- **Small rosters are solved to proven optimality.** CP-SAT reports when it has proven a roster
  optimal, and a bound when it has not, which is the gap #209 must report.
- **The backlog's next steps have native support.** Warm starts are solution hints (#197), and
  minimal conflict sets come from solving under assumptions (#199).
- **ADR-010's objective is used exactly as written.** It is linear in the assignments, with an
  auxiliary variable for a maximum.

**Harder**

- **Native code.** OR-Tools is C++ reached over JNI.
  - Five platform jars of 16 to 23 MB each come with it, so the backend jar grows by about 95 MB.
    Trimming the platforms the deployed image does not need is #157's decision.
  - The natives need a glibc-based image, not Alpine, and on Windows a Visual C++ runtime recent
    enough for the version pinned (above).
  - They are extracted to `java.io.tmpdir` when the generator starts, which must be writable. The
    executable jar loads them as packaged, which starting it showed.
  - **A crash in native code takes the JVM with it**, where a Java exception would fail one
    request. **Revisit** on the first such crash in production, by moving the solve into a separate
    process.
- **The rules are stated twice.** The domain judges them and the model restates them. The property
  tests and the use case's check are what keep the two from drifting. A new rule kind is a new case
  in the model, which the compiler insists on.
- **Generation is no longer deterministic.** A parallel search under a wall-clock limit can return
  different, equally legal rosters for the same input. ADR-033 relied on greedy's determinism to put
  the same person on a straddling shift each week. That now comes from carrying them over (#130),
  not from luck.
- **A request waits for the solve.** Two solves of at most the configured limit each fit an HTTP
  request. The stated ten minutes at the largest size do not, and need generation as a background
  job. **Revisit** when a run at the stated scale (#143) shows the limit leaving coverage or score
  on the table.
- **OR-Tools 10 is coming,** and it will be a major version with API changes for Dependabot to
  raise.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Timefold Solver** (Apache-2.0, pure Java), the candidate ADR-005 named | Small, no native code, and rostering is its flagship example. Rejected because a hard rule is a score its local search must drive to zero, so legality rests on the search finishing well rather than on the model. It also proves no bound, which #209 needs. #199's conflict sets would need a hand-written deletion filter. The rules would be restated as constraint streams either way. **Revisit** if the native dependency becomes a deployment problem: an image without glibc, or an architecture OR-Tools does not ship. |
| **A MIP solver** (HiGHS or SCIP, through OR-Tools' linear solver wrapper) | The objective is just as linear. Rejected because most of the rules are logical rather than arithmetic: no two shifts at once, rest between them, at most so many days in a row. CP-SAT states those directly, and a MIP needs big-M constraints for each. It is native code too, so it saves nothing on deployment. |
| **Keep greedy, and improve it with a hand-written local search** | No dependency. Rejected because writing and maintaining a correct optimiser is a larger risk than depending on one, and it would still prove nothing about how good its answer is. |
| **Run the solver as a separate service** | It would isolate native crashes and scale apart from the API. Rejected for now: nothing measures a need for it, and it adds a network boundary and a deployment unit. **Revisit** when #143 shows solves competing with requests for CPU or memory, or on a native crash. |
