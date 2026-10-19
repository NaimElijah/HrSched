# ADR-041: Make concurrent changes to one aggregate take turns on its row, rather than reject the later

- **Status:** Accepted
- **Date:** 2026-10-19

## Context

Every use case is one transaction (ADR-023). Each one reads an aggregate, changes it, and stores it
whole:

- a roster, with every assignment;
- an employee, with every declaration;
- an account, with every column.

Two use cases reading one aggregate at once each store over the other's change, and nothing says so.
ADR-004 accepted this as "last writer still wins" and left the fix to #33, expecting a version
column, as ADR-026 and ADR-027 did. ADR-037 and ADR-040 each left one case of it open.

By 2026-10-19 the headline case was gone: ADR-037's guard refuses a stale edit that would turn a
published roster back into a draft. What was still lost:

- **Two edits of one draft.** The second save deletes and re-inserts the assignments, so the first
  Scheduler's assignment vanishes.
- **An edit committing between a publication's read and its save.** Publication stores the
  assignments it judged over the edit (ADR-037).
- **One employee changed by an Admin and by themself at once** (F6, F26). Each save rewrites the
  children (ADR-040).
- **An account, during sign-in.** Sign-in reads the account, then compares the password, which is
  slow on purpose. A failed attempt then stores the whole row as it read it. So:
  - an Admin's disable or password reset committed in that window is undone (R17);
  - a sign-in with the old password, racing a reset, still opens a session;
  - two concurrent failures can lose one from the count towards lockout (R29).

Every command HrSched accepts is a change the server makes to what it reads: add this assignment,
grant this qualification, count this failure. No client sends a whole copy of an aggregate back to be
stored. The owner chose on 2026-10-19 how #33 is resolved.

## Decision

**A use case that changes an aggregate reads it for update. The read holds the aggregate's row until
the use case's transaction ends.** A second use case reading the same aggregate the same way waits,
then reads what the first stored. Both changes apply.

- **The ports.**
  - `RosterRepository.findForUpdate(OrganisationId, RosterId)`.
  - `EmployeeRepository.findForUpdate(OrganisationId, EmployeeId)`.
  - `AccountRepository.findForUpdate(Optional<OrganisationId>, UserId)`. It is empty for the platform
    operator, whose account belongs to no organisation, so the read stays scoped either way.
- **The adapters.** `SELECT … FOR UPDATE` on the root table alone.
  - The statement joins nothing, and the children are read by later statements. Under READ
    COMMITTED, PostgreSQL's default and the level every use case runs at, a reader that waited reads
    the children as the previous holder committed them.
  - The read throws `IllegalStateException` outside a transaction, where the row would be released
    as soon as it was read. It also throws in a read-only transaction, where PostgreSQL refuses to
    hold a row at all. `lockPublicationsOf` refuses the same way.
- **Where it is read for update.**
  - Rosters: `assign` and `unassign`, and `publish`, after the publication lock.
  - Employees: the six use cases that change an employee.
  - Accounts: account administration and password resets, after the administration lock where one
    is taken. Also changing one's own password.
  - A use case named `find…` never reads for update. It runs read-only, and holds nothing.
- **Sign-in decides on the account as it is once held.**
  - It keeps its single unlocked read and its single password comparison, made whether or not the
    name exists.
  - Only then does it read the account again for update, and it decides on that copy. It compares
    again only if the stored hash changed in between.
  - Holding the row through the comparison would have made a second simultaneous attempt on a real
    name slower than one on an unknown name, which tells an attacker which names exist. It would also
    have let a stream of guesses stall an Admin's disable.
- **A change that reaches a roster which is no longer a draft is answered 409, not 400.** After
  waiting, the change finds the roster published or superseded, which is a conflict with what
  somebody else did. The caller resolves it by reloading. ADR-037 deferred this to #33.

**The lock order cannot form a cycle.**

- An advisory lock (publication, or administration) is always taken before a row, and never while a
  row is held.
- No use case holds two aggregates' rows.
- Publication's supersession updates published rows. Only publications touch those, and they take
  turns under the publication lock (ADR-027).

**No version column.** Rejected below, with the event that would bring it back.

## Consequences

**Easier**

- **Concurrent changes to one aggregate behave as if made one after the other.** Both apply, and
  nobody is asked to retry a change that conflicted with nothing.
- **Account protections cannot be undone by a racing sign-in.** A racing sign-in cannot reverse a
  disable or a reset, cannot open a session with the password a reset replaced, and cannot drop a
  failure from the count.
- **Nothing for a client to carry, and no migration.** The schema is unchanged.

**Harder**

- **This is a port change inward of the adapters.** Like ADR-027, it qualifies ADR-003's claim that
  the storage decision could change nothing inward of the ports.
- **Changes to one aggregate queue.**
  - A publication waits behind an edit of its own draft.
  - While it waits it holds the publication lock, so the organisation's other publications wait too.
  - The wait is bounded by one edit, and nothing has measured it yet (#143).
- **Nothing in the schema makes a use case read for update.** A new use case that changes an
  aggregate it read with `findById` brings the lost update back. The service tests' fakes record
  which read a use case made, so they catch it there.
- **A stale view goes undetected.** A client acting on what it last saw has its change applied to
  what is now stored. That is right for a change the server makes, and would be wrong for a client
  storing a whole copy, which is the trigger below.
- **Changing one's own password holds the account's row through its password comparisons.** Only
  that account's own sign-ins and administration wait for it.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **A version column, `WHERE version = ?`, and a 409 (#33 as written)** | It refuses the second of two edits that do not conflict, such as two different assignments, and each refusal has to be retried. It would also thread a version through every constructor and every transition of `Roster`, `Employee` and `Account`, and through the ports. A failed sign-in racing an Admin would answer 409. |
| **One advisory lock per organisation for every change** | It makes edits of different rosters, and of different people, wait for each other for no reason. An advisory lock is also invisible in the SQL that it protects. |
| **Targeted `UPDATE`s for sign-in** (`failed_sign_ins = failed_sign_ins + 1`) | It moves `LockoutPolicy` out of the domain and into SQL, and it fixes only the account. |
| **`SERIALIZABLE` isolation** | PostgreSQL aborts one of the two transactions, which then has to be retried: the version column's refusal by another route, as ADR-027 found for publication. |
| **`FOR NO KEY UPDATE`** | It would let another transaction insert a row referencing the held root while it is held. Nothing does that today, so it would buy nothing, and `FOR UPDATE` is the form a reader recognises. |
| **Reading the account for update at the start of sign-in** | The timing it would leak, and the stall it would hand to anyone guessing, are given under Decision. |

**Revisit when** a command starts carrying a client's whole copy of an aggregate to be stored, such
as a diff applied to what a Scheduler last saw (#201). A version is then what tells a client its copy
is stale, and it would sit beside these row locks, not replace them. Also revisit if #143 measures
the time changes spend waiting for each other as a problem.
