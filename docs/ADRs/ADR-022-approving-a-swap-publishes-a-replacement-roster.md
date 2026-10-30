# ADR-022: Approve a swap by publishing a replacement roster, never by editing one

- **Status:** Accepted
  - **Amended 2026-10-30:** [ADR-023](ADR-023-the-transaction-boundary-is-the-use-case.md) made
    each use case one transaction, so a refused approval leaves no draft behind and approving a
    swap is atomic. The first two "Harder" entries no longer hold, as
    [ADR-039](ADR-039-lapse-pending-swaps-in-the-publication-that-supersedes-their-roster.md) also
    says. Q22 is answered: an employee may offer a transfer or a two-way exchange (F7). The
    transfer is built, and the exchange is [#82](https://github.com/NaimElijah/HrSched/issues/82).
- **Date:** 2026-09-01

## Context

F7 says an Employee may *"request a swap of one of their assignments with another employee"*, and
Q11 answered who decides: a **Scheduler** approves, and the colleague is informed rather than asked.

That collides with R6 head-on. A swap is about a shift somebody is actually working, which means a
**published** roster — and a published roster may not be edited, because people arrange their lives
around it. There is no override anywhere in this system and none was going to be introduced for
this.

The requirement is also ambiguous in a way nobody has resolved. "Swap … with another employee"
reads either as a **transfer** (my shift becomes yours) or as a **two-way exchange** (my shift
becomes yours and one of yours becomes mine). Q11 settled the approver, not the shape.

## Decision

**Approving a swap builds a new roster and publishes it.** The replacement carries the same work
with one assignment handed over; publication supersedes the roster the request was raised against,
exactly as any other publication does. Nothing is edited, so R6 holds without an exception.

**It publishes by calling `PlanRostersUseCase`, not by repeating what publishing does.** This is
the load-bearing part. Approving has to check the constraint set, supersede what it replaces, and
record both — all of which publication already does. Calling it means "a roster that breaks a rule
cannot be published, and there is no override" is not merely also true of swaps; it is the same
code. `SwapServiceTest` wires a real `SchedulingService` rather than a stub for that reason: a
stubbed port would prove only that the class calls something.

**A swap is modelled as a transfer, not a two-way exchange.** It is the narrower reading, it needs
no second assignment to exist, and it composes with the roster rules without inventing anything.
This is **not** presented as the settled answer — it is recorded as **Q22**.

**Eligibility is decided at approval, never at request.** This was not the original design and the
end-to-end test is what corrected it: deciding whether a colleague may cover a shift means reading
the workforce directory, and an Employee may not, because listing colleagues discloses their
unavailability. Asking is not deciding. A Scheduler has that access, and approval is the only point
at which the answer has to be true.

**Swaps get their own service and controller.** `SchedulingService` already implements four driving
ports over six collaborators; a fifth workflow would have made a class nobody can hold in their
head. The eligibility rule shared by manual assignment and swap approval was extracted to
`AssignmentEligibility`, because a rule enforced on one of two paths is not enforced.

## Consequences

**Easier**

- F7 exists, and it cannot become a way around the working-time rules that generation cannot get
  around either. A swap that would break a daily limit is refused with the violations listed, and
  the roster people are already working to is untouched — asserted directly.
- The history stays honest. The superseded roster still says Ada worked Monday; the replacement says
  Grace does. Neither is rewritten, so "who was rostered when we decided X" remains answerable.
- Swap approval, manual assignment and generation all reach publication through one door, so a
  future change to what publishing checks applies to all three at once.

**Harder**

- **A refused approval leaves a draft behind.** The replacement roster is saved before publication
  is attempted, because publication takes an identity; if the swap breaks a rule, the draft stays as
  a `DRAFT` in the organisation's roster list. It is visible, it binds nobody, and it shows a
  Scheduler what was refused — but it is litter, and the alternative was duplicating the constraint
  check to pre-validate, which is the thing this ADR exists to avoid.
- **Approving is not atomic.** It saves a roster, publishes it, saves the request and sends two
  notifications, with no transaction around any of it. A failure part-way leaves the roster
  published and the request still pending. Every read-modify-write in this codebase has that shape
  (`architecture.md` records it), and the remedy is the same one: ADR-004.
- **The counterparty gets no say.** Q11 chose that — informed, not asked — and this implements it
  faithfully, which means a Scheduler can hand somebody a shift they have not agreed to. That is a
  product decision this ADR carries out rather than one it makes.
- **A request may name somebody who cannot cover the shift**, and nobody finds out until a Scheduler
  answers it. That is the price of not letting an Employee read the directory, and it is the right
  price, but it makes a class of request that is guaranteed to be refused.
- `SwapService` depends on a driving port of its own context. That is legal and there is no cycle,
  but it is the first place here where one use case composes another, and it is worth knowing before
  somebody assumes services never call services.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Edit the published roster in place** | Simplest by far, and what most rota systems do. Rejected: it is exactly what R6 forbids, and for a good reason — the roster is the thing people planned around, so changing it silently is how somebody turns up to a shift that is no longer theirs. |
| **Duplicate the constraint check inside `SwapService` and publish only if it passes** | Avoids the leftover draft. Rejected because it creates a second implementation of "may this be published", and the day the two disagree is the day a swap publishes something generation would have refused. The litter is cheaper than the divergence. |
| **Let the counterparty accept before a Scheduler sees it** | What "swap" means in most products, and arguably fairer. Rejected because Q11 answered otherwise, and re-deciding an answered question in code is the thing this repository's rules forbid. **Revisit if** Q11 is reopened. |
| **Model it as a two-way exchange** | The other reading of F7. Rejected for now because it needs a second assignment to exist, doubles every eligibility and constraint check, and answers a question nobody has asked. **Revisit when Q22 is answered** — the aggregate would gain a second shift, not change shape. |
| **Check eligibility when the request is raised** | Fails fast, and is what was built first. Rejected once the end-to-end test showed what it costs: an Employee cannot raise a request at all without being granted the right to list their colleagues, which discloses everybody's unavailability to everybody. |
| **A `swaps` bounded context of its own** | Symmetrical with `notifications`. Rejected: a swap is about an assignment on a roster, which is `scheduling`'s aggregate, and it would need to reach into it for everything. |
