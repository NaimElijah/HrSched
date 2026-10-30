# ADR-054: Run a use case read-only when its name starts with `find`

- **Status:** Accepted
  - **Amended 2026-10-30:** The one read named otherwise is gone.
    `ConfigureConstraintsUseCase.currentConstraints` is now `findCurrentConstraints`, so it runs
    read-only like every other read
    ([#254](https://github.com/NaimElijah/HrSched/issues/254)). It was the only read among the
    driving-port methods not named `find…`, so the exception this record names no longer exists.
- **Date:** 2026-10-30

## Context

[ADR-023](ADR-023-the-transaction-boundary-is-the-use-case.md) makes every driving port method one
transaction, applied by a proxy at the composition root. It says nothing about whether that
transaction may write. The proxy has to decide, and the decision was made in code without a record.

Forces:

- **One query runs on almost every request.** `ActorAuthenticationConverter` calls `findActor` on
  every request whose token verifies ([ADR-011](ADR-011-issue-our-own-access-tokens.md)). Without a
  read-only flag, each such request opens a read-write transaction before the handler opens its own.
- **The application layer may carry no annotation to say a method reads.** The architecture test
  forbids Spring there ([ADR-003](ADR-003-hexagonal-architecture-with-ddd.md)). The proxy decides
  from what it can see, and the only signal available is the method's name.
- **The flag is enforced by the database.** Spring hands it to the connection, PostgreSQL begins
  the transaction read-only, and it refuses any write in it with SQLSTATE 25006.
- **A read-only transaction cannot hold a row.** `findForUpdate` refuses to run in one, because
  nothing would hold the row until the use case ends ([ADR-041](ADR-041-make-concurrent-changes-take-turns-on-the-row.md)).
- **One use case cannot be a single transaction.** Generation holds no connection while it solves
  ([ADR-045](ADR-045-generate-outside-the-use-cases-transaction.md),
  [ADR-046](ADR-046-generate-in-the-background-and-keep-the-job-in-postgresql.md)).

## Decision

**A driving port method whose name starts with `find` runs in a read-only transaction. Any other
runs in a read-write one.** This extends ADR-023: the boundary is unchanged, and this decides what
the transaction may do. `TransactionalUseCases` gives every method `REQUIRED` propagation and sets
the read-only flag from the name alone.

- **A query is named `find…`, and a method named `find…` only reads.**
- **A query named otherwise still works.** It gets a read-write transaction, so the name is the
  only thing that makes a read cheap.
- **One read is known not to be named `find…`:
  `ConfigureConstraintsUseCase.currentConstraints`.** It only reads, serves
  `GET /api/constraints`, which the Rules tab calls on every load, and so runs read-write today.
  It is the only one of the driving-port methods not named `find…` that reads. The rename is a
  scheduling change, tracked in [#254](https://github.com/NaimElijah/HrSched/issues/254), which
  also removes this exception.
- **One method is left outside any transaction: `PlanRostersUseCase.startGeneration`.**
  `TransactionalUseCases.transactional` takes the methods to leave alone, and for those it answers
  no transaction attribute at all (ADR-045). `UseCaseConfiguration.startingAGeneration()` picks the
  method out by reflection on the driving port, so a rename fails startup rather than quietly
  putting the solve back in a transaction. The proxy compares by name and parameter types, because
  it is asked about the implementing class's method and the exemption names the interface's. An
  overload of the same name is still transactional.
- **Another exemption needs an ADR.** `backend/CLAUDE.md` says so.

## Consequences

**Easier**

- **The per-request actor read costs a read-only transaction,** not a read-write one, with no
  annotation anywhere in the application layer.
- **The claim is checked by the database.** A method named `find…` that writes is refused by
  PostgreSQL and stores nothing.
  `TransactionalUseCasesTest.GivenAMethodNamedFindThatWrites_WhenCalled_ThenTheDatabaseRefusesTheWrite`
  pins it.
- **The exemption is loud.** It is resolved by reflection, so removing the method fails startup.

**Harder**

- **A writing use case named `find…` is broken, and only when it runs.** A use case named
  `findOrCreate…` would get a read-only transaction and its write would be refused with 25006. The
  failure is at run time, on the first call that writes. No compile step, startup check or
  architecture rule looks at the name.
- **A query named otherwise is slower than it needs to be, silently.** `TransactionalUseCases`
  treats any other name as a write, so it opens a read-write transaction and nothing says so.
- **A `find…` use case cannot take a row lock.** `findForUpdate` is refused in it. A method that
  must lock a row is not a `find…`, whatever it returns.
- **The naming rule is a convention a new author has to know.** It is written down in this record,
  the javadoc on `TransactionalUseCases` and `backend/CLAUDE.md`, and nowhere the compiler reads.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Every use case read-write** | No convention to know. Rejected because every authenticated request would open a read-write transaction for `findActor`, before the handler opened its own. |
| **`@Transactional(readOnly = true)` on the service methods** | The idiomatic answer. Rejected for ADR-023's reason: it puts the framework inside `..application..`, which the architecture test fails on. **Revisit if** that rule is ever deliberately relaxed. |
| **`NEVER` or `SUPPORTS` propagation for generation** | Rejected by ADR-045: both open an empty transaction scope, and the first repository call inside would bind a connection to the thread for the whole solve. |

**Revisit when** a method named `find…` is first found to write, or a second read use case not
named `find…`, beyond `currentConstraints` above, is found holding a read-write transaction on a
request path. Either means the name is no longer enough, and a check on the name or a marker the
proxy can read is worth its cost.
