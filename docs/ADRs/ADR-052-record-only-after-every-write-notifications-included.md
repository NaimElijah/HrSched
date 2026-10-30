# ADR-052: Record an audit line only after every write, a notification included

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

An audit line is a log line (ADR-018). Nothing rolls it back. #133 drew the consequence: a use
case refuses before its first write, and records after its save. A line written before a refusal
that rolls the change back is a record of something that did not happen.

The test-reviewer scan (#252, finding N-8) found that a notification broke that rule without
looking like a write.

- **Notifications are writes.** `Notifier` stores a notification row through the notifications
  context, inside the calling use case's transaction (REQUIRED propagation, ADR-023). That store
  can fail.
- **Scheduling recorded before it told.** `SwapService` recorded `SWAP_REQUESTED` and
  `SWAP_REJECTED`, then notified. `publish` recorded each `SWAP_LAPSED`, then notified, and
  recorded each `ROSTER_SUPERSEDED` before saving its own roster, which ADR-027's
  `RosterPublicationConflictException` can still refuse.
- **An approval told last.** `SwapService.approveSwap` notified both parties after
  `publishApproving` returned. By then the publication had already recorded `ROSTER_SUPERSEDED`
  and `ROSTER_PUBLISHED`.
- **Two ADRs described the old order:**
  - ADR-018 places `ROSTER_SUPERSEDED` "inside the supersede loop".
  - ADR-039 says publication records each `SWAP_LAPSED` and then tells, that the conflict exception
    is the one refusal that can follow a record, and that `SwapService` records the approval and
    then tells.

A failed telling therefore rolled back the change, and left behind the lines that announced it.

## Decision

**Every write a use case makes runs before it records anything, and a notification counts as a
write.**

- **The swap use cases.** `requestSwap` and `rejectSwap` save, tell, then record.
- **`publish`:**
  1. It saves the approval it settles, the lapses, the supersessions and its own roster.
  2. It tells both parties of each lapse, then both parties of the approval it settles, when there
     is one. Lapses come first, so that the approval tops each party's inbox, which lists the newest
     first.
  3. Only then does it record: one `ROSTER_SUPERSEDED` per demoted roster, `ROSTER_PUBLISHED`, and
     one `SWAP_LAPSED` per lapse.
- **`approveSwap`.** Publication tells the parties of the approval it settles. `SwapService`
  records `SWAP_APPROVED` once `publishApproving` returns, when nothing is left to write.
- **What is recorded is unchanged,** and so is the order of the entries. Only when they are written
  has moved.

This replaces where ADR-018 places `ROSTER_SUPERSEDED`, and ADR-039's order of recording and
telling. Both decisions otherwise stand:

- each change is recorded through a per-context `AuditTrail` port, and each demoted roster gets an
  entry of its own;
- a publication lapses the requests waiting on what it supersedes.

## Consequences

- **A refused or failed write leaves no scheduling line behind.** That includes a conflicting
  publication and a notification that cannot be stored. The ordering tests use a
  `FailingNotifier`, which fails from a chosen telling on, plus a publication whose own save is
  refused. Each asserts that nothing at all is recorded:
  - `SwapServiceTest.GivenATellingThatCannotBeStored_WhenRequestingASwap_ThenNothingIsRecorded`,
    `…WhenApprovingASwap…` and `…WhenRejectingASwap…`;
  - `SwapServiceTest.GivenTheSecondTellingCannotBeStored_WhenRejectingASwap_ThenNothingIsRecorded` and
    `…WhenApprovingASwap…`;
  - `SchedulingServiceTest.GivenATellingThatCannotBeStored_WhenAPublicationLapsesASwap_ThenNothingIsRecorded`,
    `…GivenTheSecondTellingCannotBeStored_WhenAPublicationLapsesASwap_ThenNothingIsRecorded` and
    `…GivenAPublicationThatCannotBeStored_WhenItSupersedesARoster_ThenNothingIsRecorded`.
- **The order of the tellings is pinned** by
  `SwapServiceTest.GivenTwoPendingSwapsOnOneRoster_WhenOneIsApproved_ThenTheLapseIsToldBeforeTheApproval`.
- **Publication now tells of an approval.** `SchedulingService` already held the `Notifier` for
  lapses, so no new collaborator is needed. Telling of an approval is part of publishing one, as
  storing the approval already was (ADR-039).
- **One gap remains: `NOTIFICATION_RAISED`.** The notifications context records it as soon as it
  stores a row. When scheduling tells two people and the second store fails, the first row rolls
  back, but its line stays. Call order cannot fix that.
  - **The trigger to revisit:** a durable audit trail written in the same transaction as the change
    (#31). It would roll back with the change and make this ordering unnecessary.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| Publication hands back the entries it would record, and `SwapService` records them after telling | It puts audit records into the `PlanRostersUseCase` port's signature. An instrumentation concern would become part of a driving port's contract, for one caller. |
| Each audit adapter defers its lines until the transaction commits | It would also close the `NOTIFICATION_RAISED` gap. But it changes when every audit line in all four contexts is written, not only the lines in scheduling that had the bug. A line could also be lost if the process stops between the commit and the write. When lines are written belongs to the decision on a durable trail (#31), not to this fix. |
| Leave the order, and accept a stray line when a telling fails | It records something that did not happen, which is exactly what #133 ruled out. |
