# ADR-004: Store every aggregate in PostgreSQL, behind the driven ports

- **Status:** Accepted
  - **Amended 2026-10-30:** Roster history is now append-only after all:
    [ADR-037](ADR-037-keep-roster-history-as-a-record-in-time.md) derives a roster's status from
    the moments it was published and superseded, each written once, so superseding a roster no
    longer changes it in place. "Last writer still wins" is replaced by
    [ADR-041](ADR-041-make-concurrent-changes-take-turns-on-the-row.md): a use case that changes an
    aggregate reads it with `findForUpdate`, so a concurrent change waits and both apply.
  - **Amended 2026-10-30:** More of this record has been overtaken, and the decision stands.
    - "Every lookup takes an `OrganisationId`" (the schema paragraph) has exceptions, and "every
      table does carry a foreign key to `organisation`" does not hold for the session tables:
      [ADR-055](ADR-055-read-an-account-or-session-without-an-organisation-where-none-is-known.md)
      lists both.
    - Row-level security, rejected "for now", had its trigger fire and was rejected again in
      [ADR-011](ADR-011-issue-our-own-access-tokens.md).
    - "Two constraints of the same kind in one set" is no longer something the domain permits:
      `ConstraintSet` refuses it (R24, [#96](https://github.com/NaimElijah/HrSched/issues/96)).
      The schema still permits it, so that invariant is held by the domain alone, and a set stored
      with two of a kind fails on reading as a fault in stored data, naming the set. Revising,
      generating, publishing and reading the rules each read the current set first, so its
      organisation can do none of them until the rows are fixed by hand.
    - `EXCLUDE` constraints, rejected "in this change", have been adopted:
      [ADR-026](ADR-026-make-overlapping-published-rosters-unrepresentable.md) and
      [ADR-037](ADR-037-keep-roster-history-as-a-record-in-time.md).
    - The count of adapters ("Eight") has moved. `Jdbc*Repository` classes in each context's
      `adapter.out.persistence` package are the current set.
  - **Amended 2026-10-30:** The schema now holds R7, non-overlapping unavailability, with the
    `employee_unavailability_does_not_overlap` exclusion constraint (migration `V15`). The
    alternatives table said to revisit the `EXCLUDE` for it "when the `btree_gist` extension is
    wanted for another reason", and `V2` installed `btree_gist` for the published-roster exclusion.
    The constraint is checked immediately, not deferred, and no adapter translates its violation:
    the domain refuses an overlap before anything is written.
- **Date:** 2026-09-06

## Context

Every repository in this codebase is a `ConcurrentHashMap` marked *PLACEHOLDER until ADR-004*.
That placeholder has been load-bearing for longer than it should have been, and four decisions
since have sharpened what it costs:

- **A restart is a total outage, not an empty start.** ADR-019 made the organisation a real
  record and ADR-020 refuses any request naming an organisation nobody onboarded. Together they
  mean an emptied register answers 404 to every tenant's every request. The system does not come
  back up degraded; it comes back up refusing everyone.
- **A lost notification is worse than a lost row.** ADR-021 records that somebody was told. If the
  inbox does not survive, a person was never told while the system believes they were.
- **Concurrent writes are lost.** Constraint revision and publication are read-modify-write cycles
  with no locking. Two admins can lose a revision; two publishes of one draft can both succeed. A
  lock around a hash map would be thrown away with the hash map.
- **Approving a swap is not atomic.** ADR-022 saves a roster, publishes it, saves the request and
  sends two notifications, with no transaction around any of it.
- **The domain may not meet the framework.** ADR-003 is enforced by a test that bans
  `org.springframework..` *and* `jakarta..` from `..domain..`. Any storage technology wanting
  annotations on the model is not merely discouraged here; it fails the build.
- **ADR-001 deferred this deliberately** and named the trigger: adding a persistence starter
  earlier would have made this decision by default. **This ADR is that trigger.**
- **ADR-009 asked a question and left it here.** With an in-memory store there was nothing to
  configure, so *"when ADR-004 lands, the question of defence in depth should be asked again."*

Q5 is already answered — deployment is containerised — so a store that expects to run as a
container costs nothing new operationally.

## Decision

**We store every aggregate in PostgreSQL.** It is the one relational store whose ordinary features
this model actually wants: real transactions for the read-modify-write cycles above, `timestamptz`
for a domain built on `Instant`, and check constraints expressive enough to mirror invariants the
domain already enforces in its constructors.

**We write the SQL by hand, through Spring's `JdbcClient`.** One adapter per driven port in
`<context>/adapter/out/persistence`, with explicit statements and explicit row mapping. The domain
classes are unchanged and unannotated. Every tenant predicate is written out in the query where a
reviewer can see it, rather than derived by a framework.

**We migrate with Flyway.** Versioned SQL under `src/main/resources/db/migration`, applied on
startup. The schema has a history, and the history is in the repository.

**One schema, with `organisation_id` on every aggregate root.** Shared-database, shared-schema
multi-tenancy, which is the shape ADR-009's port signatures already describe: every lookup takes an
`OrganisationId`, so every query already knows what its `WHERE` clause must be. The five child
tables do not carry it — they have no identity apart from their root and are only ever reached
through it — so their tenant scope is an invariant of the adapters rather than of the schema.

**Schema constraints mirror domain invariants, and express nothing the domain does not.** A
positive headcount, a period that ends after it starts, no duplicate assignment on a roster, a
draft roster having no approving constraint version and a published one having one. Each is already
enforced in a constructor; the database now agrees. Where the domain permits something that looks
untidy — two organisations sharing a name, two qualifications sharing a name, two constraints of
the same kind in one set, repeated swap requests between the same pair — **the schema permits it
too.** A unique index the domain does not enforce is a new domain rule smuggled in as a storage
detail.

**Foreign keys stay inside a context, and reach the tenant register.** A roster's assignment names
an employee by identity and nothing more, because scheduling never holds a workforce entity
(ADR-008); making that a foreign key would couple two contexts in the one place the code is careful
not to. Every table does carry a foreign key to `organisation`, because tenancy is kernel-level
rather than a context reference, and a row belonging to a tenant that does not exist is precisely
the hazard ADR-020 exists to prevent.

**Constraint sets are stored per version.** The primary key is `(organisation_id, version)` and
`findCurrent` reads the highest. The port is unchanged, but the debt it records — a published
roster naming a constraint version that cannot be read back — stops accumulating from today.

**Tests run against a real PostgreSQL in a container, and Docker is required to build.**
`./mvnw verify` starts one container and reuses it across the suite. There is deliberately no
in-memory fallback and no skip-when-Docker-is-absent: a check that quietly does not run still
reports green, which is the failure mode the CI workflow already refuses for path filters.

**The persistence guarantee is durability and atomicity, and nothing more yet.** Once a request
returns 2xx the change has committed and survives a crash, and a use case is one transaction so a
partial failure leaves nothing behind — see ADR-023 for where that boundary lives. Roster history is
kept but is **not** append-only: superseding a roster changes its status in place. Nothing is
purged, and no retention period is defined. Those last two are now Q23 and Q24 rather than
unexamined silence.

## Consequences

**Easier**

- The system can be restarted. That sentence has not been true before, and three ADRs were waiting
  on it.
- ADR-003's central promise is cashed: the storage decision was applied by replacing adapters, with
  nothing inward of the ports changed. The architecture test proves that rather than the commit
  message claiming it.
- The tests get materially better. Every HTTP test now exercises real SQL, so a broken row mapper
  or a forgotten tenant predicate fails the test most likely to catch it, instead of passing
  everything and failing in production.
- Every statement is visible. There is no lazy loading to reason about, no identity map, no flush
  ordering, and no first-level cache hiding a stale read.

**Harder**

- **The build now needs Docker.** A developer without a running daemon cannot run the test suite at
  all. That is a real cost and it is deliberate: the alternative is a suite that passes without
  touching a database.
- **There is more code.** Eight adapters of explicit upserts and row mapping, where an ORM would
  have generated it. The mapping for owned collections — a roster's assignments, an employee's
  unavailability and preferences — is written out and must be kept in step with the aggregate by
  hand.
- **A mapper cannot detect what changed.** `Roster` and `Employee` compare by identity alone, so an
  adapter has no cheap way to tell whether an owned collection was touched. Every save rewrites the
  children.
- **Last writer still wins.** A transaction makes each use case atomic; it does not add a version
  column. Two concurrent publishes now serialise instead of interleaving, which is the fix that was
  promised, but neither is rejected. Optimistic locking would change port signatures, which is a
  change inward of the port and therefore a decision of its own.
- **Ordering had to become a domain fact in one place.** `SwapRequestRepository` promised "oldest
  first" and a `LinkedHashMap` could honour it by accident. SQL cannot, so `SwapRequest` now carries
  the time it was raised — a small domain change forced by a storage decision, which is exactly the
  kind of leak the hexagon is meant to prevent, and worth naming rather than hiding.
- **Two more things to run.** A migration that fails on startup takes the application with it, and a
  schema in the repository is a schema that can drift from a database somebody patched by hand.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **JPA / Hibernate** | The conventional choice, and the one this repository is most likely to be pushed toward. Rejected because the domain may not carry `jakarta.persistence` annotations — the architecture test fails on them — so a parallel annotated model is required either way. Once that model exists, Hibernate contributes only lazy loading, dirty checking and an identity map: three behaviours that change how the application layer must be written, which is the coupling ADR-003 exists to prevent. **Revisit when** the hand-written mapping exceeds roughly twice its present size, or an aggregate needs a graph deep enough that assembling it by hand is the bulk of the adapter. |
| **Spring Data JDBC** | Much the closer fit of the two, with genuine support for immutable aggregates. Rejected for a specific footgun: identities here are minted by the domain (`RosterId.newId()`), and Spring Data reads a populated id as "this row exists" and issues an `UPDATE` affecting zero rows. The cure is `Persistable` or `@Version` — machinery a maintainer must know about to read the code. Its owned-collection handling is also delete-and-reinsert underneath, which is what we now do explicitly. **Revisit when** the number of aggregates makes per-adapter SQL repetitive enough to be a maintenance cost rather than a clarity benefit. |
| **jOOQ** | Type-safe SQL, and a genuinely good fit for hand-written queries. Rejected for the code generation step: a build that regenerates classes from a live schema is a second build gate, and ADR-002 has exactly one. **Revisit when** queries grow complex enough that a mistyped column name is a realistic production risk rather than an immediate test failure. |
| **An embedded database (H2, HSQLDB, SQLite)** | Needs no Docker, so the build stays runnable anywhere. Rejected because the tests would then exercise a different database from production, and the value of testing against a real store is precisely that it is the real store. H2's PostgreSQL compatibility mode is partial, and the migrations would have to be written to the intersection of two dialects. |
| **A database per tenant, or a schema per tenant** | ADR-009 rejected this and named its trigger; nothing since has fired it. Restated here so this record does not appear to re-decide it silently. **Revisit under** a customer with a regulatory requirement for physical separation, or when tenant count is small and stable. |
| **Row-level security policies** | The honest answer to the defence-in-depth question ADR-009 deferred to this record, and a genuinely independent second guarantee against a forgotten `WHERE`. Rejected for now on complexity: every transaction would have to set a session variable, the boundary that sets it becomes security-critical, and two paths need an explicit exemption — Flyway's migrations, and the `Operator`'s deliberately unscoped `findAll`. An exemption nobody understands is worse than no policy. **Revisit when** authentication lands (ADR-011) and there is a real principal to bind a policy to, or on the first review that finds a query missing its tenant predicate. |
| **`EXCLUDE` constraints for the overlap invariants** | Postgres could enforce non-overlapping employee unavailability, and one published roster per overlapping period, at the schema level. Both mirror real domain invariants and both were considered. Rejected in this change to keep the first schema readable, and to avoid a deferrable-constraint subtlety inside `publish`, which supersedes and then publishes within one transaction. **Revisit when** either invariant is found violated in stored data, or when the `btree_gist` extension is wanted for another reason. |
| **Liquibase** | Equivalent in capability. Rejected because its changelog indirection buys nothing here: the migrations are SQL, the team is one person, and Flyway lets them stay SQL. **Revisit if** a second database engine ever has to be supported from one changelog. |
| **Keeping an in-memory adapter behind a Spring profile** | Would let the application run with no infrastructure at all. Rejected because it is two truths about how the system stores data, and the one a developer exercises daily would be the one that is not real. |
