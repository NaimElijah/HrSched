# ADR-040: Withdraw a declaration by what it says, and keep nothing of it

- **Status:** Accepted
- **Date:** 2026-10-18

## Context

F26 lets the employee themself, or an Admin or a Scheduler on their behalf, withdraw:

- a span they declared they cannot work;
- a soft preference they stated.

It was confirmed as required on 2026-09-22 (#50), and is built in #88. Until now a declaration was
permanent. A mistaken one kept somebody off every roster across its span (R18), even for an Admin.

Forces:

- **A declaration has no identity.**
  - Unavailability is a bare `TimeRange` inside the `Employee` aggregate.
  - A preference is a `ShiftPreference(period, strength)` record with value equality.
  - The `employee_unavailability` and `employee_preference` rows are keyed by
    `(employee_id, ordinal)`. `JdbcEmployeeRepository` renumbers the ordinal on every save, because
    it replaces an employee's children rather than diffing them.
  - So nothing a caller could hold names one declaration across two requests.
- **Unavailability spans are unique per employee.** R7 refuses an overlapping declaration, so two
  identical spans never coexist.
- **Preferences may repeat.** R26 lets them overlap, and nothing refuses an exact duplicate.
- **A DELETE request carries no body that can be relied on.** HTTP gives one no defined meaning,
  and intermediaries may drop it. The frontend's `api.delete` sends none.
- **No retention period is set (Q23, #41).** Keeping a withdrawn declaration somewhere would be
  choosing one.
- **Unavailability is personal data.** It says when somebody is ill, caring for someone, or away.
- **Withdrawing only widens who may be assigned.** A published roster is never re-checked against
  unavailability (`AssignmentEligibility`), and nothing reads preferences yet. Removing either
  therefore cannot make any stored roster illegal.

## Decision

**We name a declaration by what it says, in the query string of a DELETE.**

| Withdrawing | Route |
| --- | --- |
| A span of unavailability | `DELETE /api/employees/{id}/unavailability?start=…&end=…` |
| A preference | `DELETE /api/employees/{id}/preferences?start=…&end=…&strength=…` |

- **The value must match exactly.** A span that only overlaps a declared one is not that
  declaration. A withdrawal removes a declaration; it never carves a piece out of one.
- **Every identical preference is withdrawn together.** Identical statements cannot be told apart,
  so withdrawing "that preference" withdraws all of them. A statement about the same span with
  another strength is a different statement, and stays.
- **Nothing matching is a 404.** The refusal is `UnknownDeclarationException`, answered as a 404
  problem detail. Its detail repeats no span.

**We keep nothing of the declaration itself.** The row is deleted.

- The audit trail records the act, `UNAVAILABILITY_WITHDRAWN` or `PREFERENCE_WITHDRAWN`, with the
  acting user and the employee's identifier only (ADR-018).
- No roster is touched.

## Consequences

- **Easy:**
  - There is no migration, and the domain types are unchanged.
  - A client withdraws exactly what the read-back routes (F6) handed it, by sending the same
    instants back.
  - A withdrawn absence stops blocking assignments and generation at once, through the same live
    read every path already makes.
- **Hard:**
  - A caller must echo a span to the precision it was stored at. PostgreSQL keeps microseconds, so
    a span declared with nanoseconds is matched by what the read-back returns, not by what was
    first sent.
  - Two people withdrawing and re-declaring the same record at once still race. The last whole-list
    save wins (#33).
  - Once withdrawn, the declaration is gone. Nothing can say what somebody had declared before; the
    audit line says only that something was withdrawn, by whom, from whose record.
  - Editing a declaration in place is impossible. A correction is a withdrawal and a new
    declaration.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| Give every declaration its own identifier, and DELETE by it | It needs a migration that backfills an identifier for every stored row. It also turns two value objects into entities, and changes `RosterableEmployee` and `ShiftPreferenceTest`'s pinned shape. All of that buys nothing a value cannot already name, because R7 makes a span unique, and duplicate preferences are indistinguishable by definition. |
| Keep a withdrawn declaration, marked withdrawn (a tombstone) | It chooses a retention policy that Q23 has not set. It keeps personal data nobody asked to keep. And every reader of unavailability would have to remember to filter it out. |
| Send the span in the body of a DELETE | HTTP gives a DELETE body no defined meaning. Proxies and CDNs may drop it, including the CloudFront distribution planned for staging (#162). The client sends none. |
| A `POST …/withdrawals` action route with a body | It works, but it names an action rather than the resource it removes. That leaves the collection with three verbs on two paths, when the read-back already put `GET` beside the `POST`. |
| Withdraw only one of several identical preferences | The survivors are indistinguishable from the one withdrawn, so the read-back would still show "it", and the person would see their withdrawal apparently fail. |

**Revisit when:**

- Q23 requires a withdrawn declaration to be kept for a period;
- a declaration gains attributes that can change on their own (a reason, an approval, a document),
  which would give it a life of its own and an identity worth storing;
- a requirement needs identical preferences told apart.
