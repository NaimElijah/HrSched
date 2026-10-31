# ADR-045: Generate outside the use case's transaction, between a read and a write

- **Status:** Accepted
  - **Amended 2026-10-31:**
    [ADR-046](ADR-046-generate-in-the-background-and-keep-the-job-in-postgresql.md) replaces
    `PlanRostersUseCase.generateDraft` with `startGeneration` and moves the solve onto a background
    thread. The proxy leaves `startGeneration` outside any transaction, and the flow that ends in
    storing the draft now runs on that thread, not in the request.
- **Date:** 2026-10-22

## Context

ADR-023 makes every driving port method one transaction, applied by a proxy at the composition
root, and names its own exception: "A long-running use case holds a connection for its whole
duration. Roster generation is the obvious candidate. [...] a real solver (ADR-005, ADR-010) would
need the generation step moved outside the transaction, and this decision would have to be
revisited." ADR-044 brings that solver.

Forces:

- **A solve takes seconds, by design.** Each of its two solves may run up to a configured limit,
  and at the stated scale it will use all of it.
- **A transaction holds a pooled connection for as long as it is open.** The pool is HikariCP's
  default of ten. Ten Schedulers generating at once, the stated scale for one organisation, would
  hold every connection for the length of a solve, and every other request would wait behind them.
- **Generation needs a consistent read and an atomic write, not one transaction around both.** It
  reads shifts, candidates, rules and the rosters beside the draft, and it stores one new draft.
  PostgreSQL runs at READ COMMITTED, so each statement already takes its own snapshot. One
  transaction around the reads never gave them a common one.
- **A draft governs nothing.** Assigning by hand already lets a draft hold a working-time breach,
  the draft check reports a draft's breaches without refusing it, and publication judges everything
  again under the publication lock. A draft stored from reads that changed during the solve is a
  state drafts already reach today.
- **The application layer may not touch Spring** (ADR-003), which is why ADR-023 wraps services
  rather than annotating them.

## Decision

**Generation reads in one transaction, solves in none, and writes in another.** This amends ADR-023
for this one use case. Every other use case remains one transaction.

- **A driven port, `UnitsOfWork`, gives the use case two units:** `reading` and `writing`, each
  taking a `Supplier` and running it as one transaction (read-only and read-write). It is JDK types
  only. `TransactionalUnitsOfWork` implements it in `scheduling.adapter.out.persistence`, with
  `TransactionTemplate`.
- **The proxy leaves `generateDraft` alone.** `TransactionalUseCases.transactional` takes the
  methods to run outside any transaction. For those it answers no transaction attribute at all, so
  the interceptor never begins one. `UseCaseConfiguration` names `PlanRostersUseCase.generateDraft`,
  resolved by reflection at startup, so a rename fails startup rather than quietly putting the solve
  back inside a transaction.
- **`generateDraft` becomes:**
  1. authorise;
  2. read, in a reading unit: the request, with the work carried over (#130);
  3. generate, with no transaction open and no connection held;
  4. judge the answer (#28);
  5. store the draft, in a writing unit;
  6. record it.
- **The draft is stored as generated, even if what it was generated from changed meanwhile.**
  Publication judges it again.
- **The CP-SAT generator refuses to solve inside a transaction**, as the repository refuses to lock
  outside one. Anything that puts the solve back inside one fails loudly, on the first request.

## Consequences

**Easier**

- **A solve holds no connection.** A pool sized for the interactive load is enough, however long the
  solves.
- **The cost is in one place.** The controller, the driving port and every other use case are
  unchanged.
- **The guarantee is checked three ways.** The use case's test pins the order: read, generate
  outside any unit, then store inside a writing unit. The proxy's test pins that an exempted method
  sees no transaction. The generator refuses to solve inside one.

**Harder**

- **One use case now says where its transactions are.** That is what ADR-023 rejected as a default,
  because every author would have to remember it. Here it is one exception, named once, and a
  mistake is loud: startup fails if the exempted method disappears, and the generator fails if it
  is ever asked inside a transaction.
- **A draft can be stored from inputs that changed during the solve.** Somebody may declare an
  absence, or the rules may tighten, while it runs. The draft check shows it, and publication
  refuses it, exactly as for a draft edited before the rules changed.
- **The audit line follows the writing unit's commit.** A failure between commit and record would
  leave a draft with no audit line. That is the safer direction for a log that cannot be rolled
  back (ADR-018).

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Leave generation in its transaction** | No change. Rejected for the pool exhaustion above. |
| **Run `generateDraft` with `NEVER` or `SUPPORTS` propagation** | Fewer lines in the proxy. Rejected because both open an empty transaction scope with synchronisation active, the manager's default. The first repository call inside it would bind a connection to the thread until the method returned, which is the whole solve. |
| **Split generation into its own service**, composed at the composition root from two proxied steps | Keeps transactions out of every service body. Rejected because both steps would become public methods on the proxied service: a read returning a driven-port type to the driving side, and a write storing assignments a caller supplies, which would have to authorise and judge again. It also moves `generateDraft` off `PlanRostersUseCase`, and the controller and some forty test call sites with it. |
| **Generate as a background job** | The answer for the stated ten minutes at the largest size. Rejected for now, because it needs somewhere to keep a job and a way to read it back, and nothing measures that a bounded synchronous solve is not enough. **Revisit** when a run at the stated scale (#143) shows the configured limit leaving coverage or score on the table. |
| **Check the draft again inside the writing unit, and refuse if its inputs changed** | Would keep "generated" meaning "legal now". Rejected because the check would race exactly as the solve does, and would turn a long solve into a failure the Scheduler can only retry. Publication is where legality has to hold, and it holds there under the lock. |
