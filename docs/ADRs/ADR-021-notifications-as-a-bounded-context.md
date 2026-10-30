# ADR-021: Make notifications a bounded context, and let it hold no words

- **Status:** Accepted
  - **Amended 2026-10-30:** The notification store is in PostgreSQL
    ([ADR-004](ADR-004-postgresql-behind-the-driven-ports.md)), so "nothing survives a restart" no
    longer holds. Delivery is still pull-only, which is Q21.
  - **Erratum 2026-10-30:** "Q22" in the consequences and in the last alternative should read Q21,
    the question about the notification channel. Q22 is whether a swap is a transfer or an
    exchange.
- **Date:** 2026-09-01

## Context

Q11 was answered on 2026-08-30: a swap is approved by a **Scheduler**, and *"the colleague is
informed rather than asked"*. `requirements.md` has carried that sentence ever since, under F7.

Nothing in the system can inform anybody. There is no email, no push, no inbox, and no outbound
integration of any kind — `architecture.md` records that the backend makes no outbound calls at
all. So "informed" was an answered question with no mechanism behind it, and F7 could not be built
honestly without deciding what the word means.

Three shapes were available: pretend (log a line and call it telling somebody), defer (build the
swap, raise the gap as a new question), or build the smallest thing that actually informs a person.

## Decision

**We add a `notifications` bounded context**: its own hexagon, its own `AuditTrail`, its own web
adapter. New code that fits no existing context is a new bounded context and a new ADR, and this
fits none — it is not scheduling (it outlives the roster that caused it), not workforce (it is not
about who somebody is), and not access.

**A notification carries identifiers, a kind, a time and a read flag. There is no message field.**
This is the same guarantee `AuditedChange` makes, made the same way — by having nowhere to put a
sentence — and for a sharper reason. A notification store is a second database of who did what to
whom, kept outside the aggregates that own that information; a free-text field is exactly how it
would quietly become a copy of the roster with people's names in it. `NotificationTest` asserts it
by reflection over the type's own fields, because a future field is what would break it.

**The vocabulary of kinds is owned here**, even though every value today describes something that
happened in `scheduling`. Letting another context hand this one arbitrary text is the same mistake
as a message field, arriving by a different route.

**Reading is strictly self-service, and it is unrepresentable to ask otherwise.** Whose inbox is
read comes from the personnel record the actor carries (ADR-012), never from a path or query
parameter, so "show me a colleague's notifications" is not a request that gets refused — it is a
request that cannot be written. `NotificationRepository` has no `findById` taking a notification
identity alone, for the same reason `EmployeeRepository` has none taking an employee identity
alone.

**`NotifyUseCase` is published but deliberately not exposed over HTTP.** It is gated by the tenant
boundary and no role, because an employee asking a colleague for a swap must be able to tell them
and no role describes that. Behind an in-process port that is right, since every caller is a use
case that already decided somebody deserved telling. On a controller it would be a way for any
caller to post anything into any colleague's inbox.

**The service takes a `Clock`.** When a notification was raised is an input the tests supply, not
an ambient fact they tolerate. `java.time.Clock` is the JDK, so the hexagon stays framework-free.

## Consequences

**Easier**

- F7 can be built without either faking "informed" or leaving it undone.
- The one thing an inbox is really for — a person seeing that something happened to them — works,
  and works through the same API and the same identity placeholder as everything else.
- A fourth context is real evidence that the context boundaries hold: adding one required no change
  to any existing context's core, and the architecture rules passed unmodified.

**Harder**

- **Nothing survives a restart, and an inbox is the worst place for that.** An audit line lost on
  restart is an operator's problem; a notification lost on restart means a person was never told,
  and the system believes they were. ADR-004 now has a case where in-memory storage is not merely
  a placeholder but actively misleading.
- **Delivery is pull-only.** Somebody is "informed" when they next open the application. There is no
  email, no push, and no unread badge anywhere outside the API — so a swap approved while somebody
  is off shift informs them when they come back, which for a rota change may be too late. This is
  the honest limit of what was built, and **Q22** now asks what channel is actually required.
- **A fourth `AuditTrail` / `AuditedChange` pair, and a fourth `ActorFromHeaders`.** The deliberate
  duplication ADR-008 chose is now visible four times, and `UseCaseConfiguration` writes three of
  the four audit ports out fully qualified.
- The kind vocabulary is a coupling in disguise: every new thing worth telling somebody about means
  a new value here, and `notifications` will keep learning the names of other contexts' events.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Log a line and call the colleague informed** | Cheapest, and mirrors how the audit trail works. Rejected because it is false. A log line informs an operator, and F7 says a person is informed; shipping it would make the requirements table say something the system does not do. |
| **A `Notifier` port with a logging adapter, no store** | Honest about the boundary and swappable later. Rejected for the same reason with an extra step: the port would be real and the promise still unkept, and "informed" would remain a word nobody could check. |
| **Raise a new open question and leave F7 unbuilt** | Consistent with how Q19 and Q20 were handled. Rejected because Q11 is *answered* — the repository owner already decided a colleague is informed — so the open question was the mechanism, not the intent, and the mechanism is small. |
| **Put notifications inside `scheduling`** | No new context, no new ADR, and every notification today is about a swap. Rejected: a notification outlives the roster that caused it, and the moment anything in `workforce` needs to tell somebody something, `scheduling` owns a concept that is not its own. |
| **A free-text `message` field** | What every notification system has, and it would let the sender say something useful. Rejected outright: it makes a store outside the aggregates into a second copy of personal data, with none of the rules that protect the first copy. **Revisit if** localisation forces rendered text — at which point the text belongs to the reader's locale, not to the notification. |
| **Push, email, or websockets** | Actually informs somebody who is not looking. Rejected as a whole subsystem with its own dependency, its own ADR, and its own compliance question, none of which is answered. **Revisit when** Q22 names a channel. |
