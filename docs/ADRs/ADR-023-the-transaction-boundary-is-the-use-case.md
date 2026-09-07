# ADR-023: Make the use case the transaction boundary, wrapped at the composition root

- **Status:** Accepted
- **Date:** 2026-09-06

## Context

ADR-004 brings a real database, and with it the question every real database asks: where does a
transaction begin and end?

- **The work that needs a transaction is already identified.** Publishing supersedes every
  overlapping roster and then saves the published one. Approving a swap saves a replacement roster,
  publishes it, saves the request and sends two notifications. Both are recorded — in ADR-022 and in
  `architecture.md` — as things that can fail half-way and leave the system inconsistent.
- **One use case is exactly one unit of work.** Every driving port method is a single thing a
  person asked for. There is no case in this codebase where half of one should survive.
- **A read must see a write made earlier in the same call.** `SwapService.approveSwap` saves a
  replacement roster and then calls `PlanRostersUseCase.publish`, which immediately reads that
  roster back by identity. A `ConcurrentHashMap` guaranteed that; a database does not, unless both
  statements are in the same transaction on the same connection.
- **The obvious answer is forbidden.** `@Transactional` on an application service would put
  `org.springframework.transaction` inside `..application..`, which the architecture test rejects.
  That rule is not incidental — ADR-003 exists so use cases stay constructible with `new` in a unit
  test, and an annotation that only means something inside a running container breaks that.
- **The next-most-obvious answer is worse.** Annotating controllers would put the boundary around
  JSON serialisation, and would leave any non-HTTP caller — including one use case calling another —
  outside a transaction entirely.

## Decision

**A transaction begins when a driving port method is called and ends when it returns.** Nothing
finer, nothing coarser.

**The boundary is applied at the composition root, not declared on the service.** `UseCaseConfiguration`
already exists so that application services carry no Spring annotation; it now also wraps each one
in a transactional proxy built from Spring's `ProxyFactory` and `TransactionInterceptor`. The
services themselves are unchanged, still annotation-free, and still constructible with `new`.

**Nested use cases join the caller's transaction.** Propagation is `REQUIRED`, so
`SwapService.approveSwap` calling `PlanRostersUseCase.publish` produces one transaction covering
both. That is what makes approving a swap atomic, which ADR-022 recorded as owed.

**A runtime exception rolls the transaction back.** Every refusal in this codebase is an unchecked
exception, so a refused act writes nothing — which is what it already claimed to do, now enforced by
the database rather than by the order of statements in a method.

## Consequences

**Easier**

- Every use case is atomic without any use case knowing it. The property is declared in one file
  and holds for every driving port, including ones not yet written.
- The read-after-write inside `approveSwap` is correct by construction rather than by accident of
  the store.
- Adding a use case does not mean remembering to add a transaction. Registering the bean is already
  mandatory — forgetting it fails at startup — and the wrapping happens there.

**Harder**

- **The audit trail can now record something that did not happen.** Audit entries are log lines
  (ADR-018), and a log line cannot be rolled back. A use case that records a change and then fails
  to commit leaves a record of a change nobody made. This is the exact inverse of the rule that a
  refused act records nothing, it is newly true because of this decision, and it is not fixed here —
  a durable trail is Q20, which is blocked on the same ADR-004 that created the problem.
- **The proxy is indirection.** A stack trace now has an interceptor frame in it, and a maintainer
  reading `SchedulingService` sees no sign that its methods are transactional. The javadoc on
  `UseCaseConfiguration` has to carry that knowledge, because the code no longer can.
- **A long-running use case holds a connection for its whole duration.** Roster generation is the
  obvious candidate. Today it is greedy and fast; a real solver (ADR-005, ADR-010) would need the
  generation step moved outside the transaction, and this decision would have to be revisited.
- **Self-invocation is not intercepted.** A service method calling another method on `this` does not
  pass through the proxy. It changes nothing today, because the boundary is per use case and a
  nested call is already inside one, but it is the classic way this pattern is misunderstood later.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **`@Transactional` on the application services** | The idiomatic Spring answer, and by far the least code. Rejected because it puts the framework inside `..application..`, which the architecture test fails on and ADR-003 exists to prevent. **Revisit if** that rule is ever deliberately relaxed — at which point this record should be superseded rather than quietly ignored. |
| **`@Transactional` on the web controllers** | Legal under the architecture rules, since adapters may use Spring. Rejected because the boundary would then include request parsing and response serialisation, and would not exist at all for a use case invoked by another use case or by a future scheduled job. |
| **An explicit `UnitOfWork` driven port the services call** | Fully explicit, framework-free, and visible in the code. Rejected because it puts transaction management in every service body, where it must be remembered every time — and a guarantee that depends on every author remembering is not a guarantee, which is the same argument ADR-009 makes about tenant scoping. |
| **A hand-written decorator per driving port** | No proxies and no reflection, so the wrapping is readable. Rejected on volume: there are seven driving ports today and each would need a class forwarding every method, which is a great deal of code whose only content is boilerplate that must be updated whenever a port gains a method. **Revisit if** the proxy ever obscures a real bug badly enough to be worth the boilerplate. |
| **Transaction per repository call** | The default if nothing is decided. Rejected because it is precisely the behaviour that makes publishing and swap approval non-atomic — each write commits alone, so a failure half-way leaves the earlier ones standing. |
