# ADR-039: Lapse pending swaps in the publication that supersedes their roster

- **Status:** Accepted
  - **Amended 2026-10-30:**
    [ADR-052](ADR-052-record-only-after-every-write-notifications-included.md) replaces the order
    in which publication records and tells. It now makes every write, a notification included,
    before it records anything, so "the one refusal that can still follow a record" no longer
    holds. Publication tells of the approval it settles, and `SwapService` records `SWAP_APPROVED`
    once publication returns. The rest stands.
- **Date:** 2026-10-15

## Context

#55 was answered on 2026-09-22 (Q33):

- **R33.** A pending swap request **lapses** when the roster it names is superseded, and its
  parties are told.
- **R32.** An assignment has at most one pending swap request.

Until now a request outlived its roster. It stayed `REQUESTED` against a roster nobody worked to any
more. Approving it failed with a 400 about the roster, and only a rejection could clear it (#94).

Forces:

- **A request names a roster, and every published version of a roster has an identity of its own.**
  "The roster it names is superseded" is therefore a fact about one row, written by exactly one
  publication.
- **Approving a swap is itself a publication** (ADR-022). The replacement it publishes supersedes
  the very roster the approved request names, so that request is pending, on a roster being
  superseded, at the moment the publication runs.
- **A publication's guarantees rest on taking turns** (ADR-027) and on being one transaction
  (ADR-023). Audit lines are log entries that outlive a rollback (ADR-018), so a write that can
  still fail belongs before the records describing what it was part of.
- **A decision was final in the domain but not in the store.** The swap upsert overwrote any
  status, so two writers that each read a request as pending each saved their decision, and the
  last one won.
- **The in-memory fakes do not roll back.** Three service tests assert that a refused approval
  leaves the request pending, and they hold because the approval is saved only after the
  publication succeeds.

## Decision

**A publication lapses every request still pending on each roster it supersedes, inside its own
transaction.**

- Before it records anything, `SchedulingService.publish` reads
  `SwapRequestRepository.findPendingNaming` for each roster it supersedes, and saves each request
  `lapsed()`.
- Once the published roster is stored, it records `SWAP_LAPSED` for each, and tells the requester
  and the counterparty through `Notifier.swapLapsed`.
- So a lapse refused part-way leaves no audit line behind. The one refusal that can still follow a
  record is ADR-027's `RosterPublicationConflictException`, which needs a writer that skipped the
  lock.
- **`LAPSED` is a final state of its own.** It is not `REJECTED`, because nobody decided it.
- Approving a lapsed request is a 409, "No longer pending". The same answer is given to a request
  decided twice.

**Approval names the request it settles, already decided.**

- `PlanRostersUseCase.publishApproving(actor, organisation, replacement, approved)` is `publish` with
  one addition: it stores the approved request **before** the lapse looks for pending requests, so
  the lapse never takes it for one of them.
- It refuses, before writing anything, an argument it could not have been asked to settle:
  - one not `APPROVED`, or of another organisation;
  - one naming a roster this publication does not supersede;
  - one that is not the request stored and waiting under its identity, with the same roster, shift
    and parties;
  - one the replacement does not carry.
- `SwapService` then records `SWAP_APPROVED` and tells the parties, after the approval is stored.

**A decision is final in the store too.** The swap upsert updates only a row still `REQUESTED`.
Otherwise it reads the row back under the same organisation, and refuses with
`SwapNotPendingException` naming the stored status.

**Raising and rejecting a swap take turns with publications.** `requestSwap` and `rejectSwap` take
the publication lock after authorising and before reading, as `approveSwap` already did. This extends
ADR-027's lock beyond publishing.

- **Without it on raising,** a request read against a roster that a concurrent publication then
  supersedes would be stored after the lapse query ran. It would stay `REQUESTED` for ever.
- **Without it on rejecting,** a rejection committing between a publication's lapse query and its
  lapse would turn that lapse into a decision over a decision. The store refuses that, and the whole
  publication would go with it.

**One pending request per assignment (R32), and the assignment is the roster, the shift and the
person on it.** Whoever it asks does not matter.

- `requestSwap` refuses a second pending request with `SwapAlreadyRequestedException`, a 409
  "Already requested". It checks under the publication lock, through the same `findPendingNaming`
  the lapse uses.
- The partial unique index `swap_request_one_pending_per_assignment` (V8) is the backstop for a
  writer that skipped the lock, as `roster_one_published_per_moment` is for R11. The adapter answers
  its violation with the same refusal.
- **The roster is part of the assignment** because a request names one. The same shift carried by
  a replacement roster is another assignment. A shift straddling two authoritative rosters could
  therefore have a request pending on each: what such a shift means for the person on it is #135,
  the owner's open question, and this leaves it open rather than deciding it here.

**Requests stored before these rules are brought into line by V7 and V8.**

- **V7** lapses those still pending on a roster already superseded.
- **V8** keeps the oldest of several pending for one assignment, in the order a Scheduler works the
  queue, and lapses the rest. That is the only way it can build its index over what was already
  there. `LAPSED` is the only final state a Scheduler did not choose. Nothing is deployed yet, so no
  real request is affected.

Neither migration tells anybody: a migration has no caller to tell anybody on behalf of.

**What this does to earlier decisions:**

- **ADR-022** still holds: approval publishes through the same code as any publication. Its
  consequence that "approving is not atomic", leaving a draft behind on refusal, has not described
  the system since ADR-023. It is not rewritten; this record says so.
- **ADR-027's lock** now has four callers rather than two.

## Consequences

**Easier**

- **A request can no longer outlive its roster.** A Scheduler's queue holds only requests that can
  still be approved, and both parties learn when one stops being one.
- **Every way a request ends is final in storage as well as in the domain.** Two Schedulers, or a
  Scheduler and a publication, can no longer leave a decision that contradicts what its parties were
  told.

**Harder**

- **Approving one swap lapses every other pending request on that roster**, because the replacement
  supersedes the roster they name. That is R33 as written. Each requester is told, and may raise
  their request again against the replacement. Revisit if the owner decides a swap approval should
  carry the other requests across to the replacement.
- **A requester who wants to ask somebody else must wait** until a Scheduler answers or the roster
  is replaced, because withdrawing a request (F29) is not built yet (#91).
- **`SchedulingService` now depends on the swap store and the notifier.** Both are driven ports of
  its own context, so no architecture rule objects, but publication now knows that swap requests
  exist.
- **`publishApproving` exists for one caller.** It is on the driving port because that is the only
  door into publication, but no controller calls it. A future caller that passes a request it has
  not checked is refused before anything is written.
- **Raising and rejecting a swap wait behind a publication in flight.** They are one organisation's
  writes waiting for each other, which the publication lock already accepted for publications.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Save the approval before publishing** | The simplest way to keep the approved request out of the lapse. Rejected because it makes "a refused approval changes nothing" true only through rollback. The three service tests asserting it against fakes would have to move to HTTP tests, and the order of the writes would stop saying what the use case guarantees. |
| **Pass only the approved request's identity to the publication** | A smaller signature. Rejected because the publication would then lapse everything except that identity and leave the decision to the caller, so a caller that forgot to decide it would leave a request pending on a superseded roster. Passing the decided request lets the publication store it, and leaves nothing to forget. |
| **Infer the approved request from the replacement** (its assignment removed, the counterparty's added) | No new parameter. Rejected because a Scheduler can build that same roster by hand. Its publication would then leave a request pending on a superseded roster, approved by nobody. |
| **Lapse lazily, when a request is read** | No change to publication. Rejected because R33 says the parties are told, and telling needs a moment at which the lapse happens. |
| **One pending request per shift and person, across rosters** | Closer to "one place to be" for a shift carried by two rosters (R35). Rejected because it decides #135, an open question of the owner's, and because a request names one roster, whose replacement is another assignment. |
| **Mark duplicates found by V8 `REJECTED`** | A familiar state. Rejected because it would claim that a Scheduler declined them. |
| **Lapse in a separate step after the publication commits** | Keeps publication ignorant of swaps. Rejected because the lapse would happen outside the transaction that caused it. A failure between the two would leave a request pending on a superseded roster, and no single use case would own it. |
