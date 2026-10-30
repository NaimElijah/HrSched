# ADR-027: Make publications within one organisation take turns, with a transaction-scoped advisory lock

- **Status:** Accepted
  - **Amended 2026-10-30:**
    [ADR-037](ADR-037-keep-roster-history-as-a-record-in-time.md) replaces the constraint "ADR-026's
    constraint stays" names, and records that the revisit trigger "when roster history becomes
    append-only" has fired and the lock stands.
    [ADR-039](ADR-039-lapse-pending-swaps-in-the-publication-that-supersedes-their-roster.md)
    extends the lock beyond publishing: raising and rejecting a swap take it too.
- **Date:** 2026-09-14

## Context

ADR-026 made two overlapping published rosters of one organisation unrepresentable. That stops two rosters being authoritative at once, but it does so by **refusing** whichever concurrent publication loses. Through the use cases, that refusal lands in places where it should not:

- **Two Schedulers publishing a second apart** see the later roster supersede the earlier one, as R11 says. **Publishing at the same moment**, one of them is told to reload and try again. Timing would decide between two different outcomes for the same two requests.
- **A deadlock victim waits before it is refused.** Two such writers can each wait on the other's uncommitted row, and PostgreSQL resolves that only after `deadlock_timeout`.
- **A swap approval decides from a roster it read before its transaction could see another approval commit.** It builds its replacement from the roster the request was raised against. If a concurrent approval has just superseded that roster, the replacement lacks the other hand-over. ADR-026 refuses its publication, but only because the timing happened to collide in the schema, not because the approval was checked against what is now authoritative.

The owner chose to make publications take turns, and to keep ADR-026's constraint behind that.

## Decision

**Publishing, and approving a swap, first take a lock on the organisation's publications.**

- **The port.** `RosterRepository.lockPublicationsOf(OrganisationId)`: every other publication for that organisation waits until the calling use case finishes, which ADR-023 makes the end of its transaction.
- **The adapter.** `JdbcRosterRepository` implements it as `pg_advisory_xact_lock(hashtext(scope), hashtext(organisation))`.
  - The scope is a named constant for roster publication, so this lock cannot collide with any other use of advisory locks.
  - Two organisations whose identities hash alike only wait for each other; neither can see the other's data.
- **Where it is taken.**
  - `SchedulingService.publish` takes it before it reads the draft, the rules or the rosters it supersedes.
  - `SwapService.approveSwap` takes it before it reads the request and its roster. An approval that waited therefore reads what the first one committed: a request already decided (409), or a roster no longer published (400).
  - The `publish` that `approveSwap` calls takes the lock again. Advisory locks are re-entrant within a session, so it already holds it.

**Transaction-scoped, not session-scoped.** It is released on commit or rollback, so no path can forget to release it. It also stays correct behind a pooler that hands out connections per transaction.

**ADR-026's constraint stays.** The lock is a convention every writer must follow; the constraint is what holds when one does not.

## Consequences

**Easier**

- **Concurrent publications behave as if they had been made one after the other.** The later supersedes the earlier, as R11 says, and nobody is refused for clicking at the same moment.
- **A concurrent swap approval decides from committed state.** It is refused with a reason that is true ("already decided", or "that roster is no longer the published one") rather than colliding in the schema.
- **Through the use cases, ADR-026's refusal and its deadlock wait are no longer reached.** Reaching them now means a writer skipped the lock, and the handler logs that at WARN.

**Harder**

- **Publications within one organisation serialise.** The lock is held for the whole of `publish`, including the constraint check and its shift lookups, so a slow publication delays every other publication in that tenant. Other tenants are unaffected.
- **This is a port change inward of the adapters.** It qualifies ADR-003's claim that the storage decision could change nothing inward, as #33 anticipated for a version column.
  - The in-memory fakes cannot lock, so the unit tests can only check the order of calls. The waiting itself is proven against PostgreSQL.
- **The lock is invisible in the schema.** Nothing stops a future use case from forgetting it. That use case would fall back to ADR-026's refusal rather than corrupt anything.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **ADR-026's constraint alone** | Keeps R11, but refuses the later of two publications made at the same moment, where a second's difference would have superseded. The loser can also sit through a deadlock timeout first. |
| **`SERIALIZABLE` isolation for publishing** | PostgreSQL would detect the conflicting reads and abort one transaction. Rejected: the abort still has to be retried, so it is ADR-026's refusal by another route. `TransactionalUseCases` builds its transaction attribute per method, so the level could be raised for `publish` and `approveSwap` alone, but that would not change it. |
| **`SELECT … FOR UPDATE` on the `organisation` row** | No extension, and visible in SQL. Rejected: that table belongs to `access`, and every insert in every context takes `FOR KEY SHARE` on it through its foreign key. Locking it for update would stall the whole tenant's writes for the length of a publication. |
| **A session-scoped advisory lock, or a lock held in the JVM** | The session lock must be released by hand, and a failure between lock and unlock leaks it. A JVM lock holds only within one process, so a second instance would race exactly as before. |
| **A version column on `roster` (#33)** | Rejects the second of two writes to one row. Two overlapping publications write different rows. #33 remains open for concurrent edits of one draft. |

**Revisit when** the time publications within one organisation spend waiting for each other becomes a measured problem (the performance requirement is still `TODO`), or when roster history becomes append-only (Q24).
