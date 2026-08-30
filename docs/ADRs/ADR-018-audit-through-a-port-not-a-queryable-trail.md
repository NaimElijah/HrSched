# ADR-018: Record every change and refusal through a driven port, and build no queryable trail

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

`requirements.md` says of tenant isolation:

> A cross-tenant read is **a security incident, not a bug**.

Until this change, a cross-tenant attempt was caught by `Actor.requireAccessTo`, translated by an
exception handler into `404 No such resource.`, and **left no trace anywhere**. Nothing counted it,
nothing logged it, and the response was deliberately identical to an ordinary miss so that nothing
derived from the status could tell them apart either. The incident the requirements take most
seriously was undetectable by construction.

Successful changes were no better recorded. Nobody could say who published a roster, who replaced
an organisation's working-time rules, or who altered somebody else's contracted hours — and that
last one matters, because `declareUnavailability` and `statePreference` can be performed by an
employee on their own record *or* by a manager on theirs, and only the actor distinguishes the two.

Three constraints shaped what could be built:

- **The application layer holds no framework.** `HexagonalArchitectureTest` forbids
  `org.springframework..` inward of an adapter, so `ApplicationEventPublisher` cannot be injected
  into a use case, and a service cannot hold a logger's worth of Spring machinery. Anything the
  application records, it records through a port.
- **This is a multi-tenant product handling personal data.** Availability, absence, contracted
  hours and names are exactly what `requirements.md` lists under an unanswered Compliance `TODO`.
  A trail that quotes them is a second copy of that data somewhere nobody thinks of as a database.
- **Nothing survives a restart** until ADR-004, and the retention rules an audit trail would need
  are part of that same unanswered question.

## Decision

**Each context gets an `AuditTrail` driven port**, in its own `application/port/out`, taking an
`AuditedChange` record. Every state-changing use case calls it **after** the repository save.
`SchedulingService` records five kinds of change, including `ROSTER_SUPERSEDED` **inside the
supersede loop** — publishing one roster silently demotes every published roster it overlaps, and
without an entry each those rosters appear to have demoted themselves.

**The subject is a bare `UUID`.** Not a name, not a description, not a free-text field. A name
cannot be put in an `AuditedChange` because there is nowhere to put one, which is a stronger
guarantee than a convention saying it should not be. Reading the trail tells you *who to ask*,
never the answer.

**The two ports are separate types, not one shared type.** Their change vocabularies differ, so
`SchedulingChange` and `WorkforceChange` make them genuinely different records rather than
duplication; and anything common would have to live in the shared kernel, which ADR-008 keeps as
small as it can be. The cost is visible in `UseCaseConfiguration`, the one place that sees both.

**The adapter writes a log event and increments a counter.** One call, both signals, which is why
no separate instrumentation port exists. Fields go on as slf4j key-value pairs so the ECS encoder
publishes them as JSON members a log store can filter on; the message itself is a constant. At
INFO, because these are state transitions and they are the record — a trail switched off by default
in production is not a trail.

**Refusals are recorded at the web boundary**, in the two `@RestControllerAdvice` classes, because
that is the only place that sees every one of them. A cross-tenant attempt is a `WARN` carrying the
actor and the organisation reached for, and increments `hrsched.access.denied{kind="cross_tenant"}`
— which is what distinguishes it from the ordinary 404 it is deliberately indistinguishable from
over HTTP. **The response is byte-for-byte what it was**: still 404, still `No such resource.`
Only the operator gains anything.

**There is no queryable audit trail, no `audit` bounded context, and no `GET /api/audit`.** Whether
an Admin should be able to read their organisation's trail, and under what retention and erasure
obligations, is **Q20** — and it cannot be answered before ADR-004 and the Compliance `TODO`.

## Consequences

**Easier**

- The security incident is detectable. `hrsched_access_denied_total{kind="cross_tenant"}` is a
  number an alert can be written against, which is a thing that did not previously exist in any
  form.
- "Was this recorded?" is a test rather than a review comment. Every service test asserts both that
  a change is recorded with the right actor and organisation, and that a **refused** act records
  nothing — an entry for something that did not happen is worse than no entry.
- No personal data reaches the log, and the type system is what stops it. Verified by running the
  application, adding an employee named "Ada Lovelace", and grepping the log: zero occurrences.
- Instrumentation stayed out of the hexagon. The services gained one constructor parameter each and
  no import from Spring, Micrometer or slf4j.

**Harder**

- **The trail is only as durable as the log.** Nothing is queryable, nothing is retained by this
  application, and an operator without a log collector has an audit trail that scrolls past. That
  is the accepted cost of not answering Q20 prematurely.
- **Every new state-changing use case must remember to call the port.** Nothing enforces it; a
  forgotten call is a silent gap. The tests cover the ten that exist, and an eleventh will need its
  own.
- **The constraint-set version is not recorded.** `CONSTRAINTS_REVISED` names the organisation as
  its subject, because a constraint set is one per organisation and has no identity of its own, so
  the trail says the rules changed but not what they changed to.
- **Two same-named types in two contexts collide where they meet.** `UseCaseConfiguration` writes
  one `AuditTrail` out in full. Two `@Component` adapters called `LoggingAuditTrail` collided
  harder — Spring derives a bean name from the simple class name alone, so the context failed to
  start; they are now `SchedulingAuditLog` and `WorkforceAuditLog`.
- **Auditing a read is not done at all.** Reading a roster discloses who works when, for everybody;
  no entry records that it happened.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **An `audit` bounded context: an `AuditEntry` aggregate, a tenant-scoped repository, and `GET /api/audit` for an Admin** | The complete answer, and where this goes if Q20 says so. Rejected because it asserts a requirement nobody has stated: it decides that an Admin may read the trail, and commits to a retention period, while the Compliance `TODO` is open and ADR-004 is unwritten — and the trail would itself be personal data with an erasure obligation. **Revisit when** Q20 is answered, or the first time somebody has to answer "who changed this?" and cannot. |
| **Log the refusals and nothing else** | Closes the security-blindness gap for a fraction of the work. Rejected because it leaves the other half of the question unanswered: who changed what. A log of refusals says only that the guards work. |
| **A logger in each application service** | Simplest possible. Rejected outright: `HexagonalArchitectureTest` keeps the application layer free of the framework, and a log call is untestable without capturing an appender, so "did we record it?" would stop being a specification and become a thing reviewers notice. |
| **Spring AOP or a decorator around the driving ports** | Records everything with no call sites to forget. Rejected because it can only record *that a method was called*, not what domain event happened: no pointcut can tell `ROSTER_PUBLISHED` from `ROSTER_SUPERSEDED`, and both happen inside one call to `publish`. It would also need pointcuts written against package names, which is a rule with no test. |
| **One shared `AuditTrail` in the kernel** | Half the code. Rejected because the kernel is a coupling every context pays for (ADR-008), and the shared type would need a free-text `String` change name — which is exactly the field a future author drops an employee's name into. |
| **Record the change before saving** | Marginally simpler control flow. Rejected: an entry for something that then failed to store is a record of something that did not happen, and a trail with false entries is worse than no trail. |
| **Tag the metrics by organisation** | Would let an operator see which tenant is generating refusals. Rejected on cardinality: one series per tenant per change kind grows without bound, and the same information is in the log line, where it costs nothing. **Revisit if** per-tenant alerting is ever actually needed - with a bounded tenant count. |
| **Audit reads as well as writes** | A roster read discloses personal data about every colleague, so there is a real argument for it. Rejected for now because reads outnumber writes by orders of magnitude and would bury them, and because no requirement asks for it. **Revisit when** Q20 is answered - access logging and change logging are the same conversation. |
