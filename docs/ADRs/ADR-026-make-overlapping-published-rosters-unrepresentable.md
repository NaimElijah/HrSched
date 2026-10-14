# ADR-026: Make two overlapping published rosters of one organisation unrepresentable in the schema

- **Status:** Superseded by [ADR-037](ADR-037-keep-roster-history-as-a-record-in-time.md)
- **Date:** 2026-09-14

## Context

"Exactly one roster is authoritative for any given moment" (`requirements.md`). R11 is how the application keeps that: `SchedulingService.publish` reads the organisation's rosters, supersedes every published one the new roster overlaps, and then publishes it, all in one transaction (ADR-023).

**That holds for one publication at a time, and for nothing else.**

- Under PostgreSQL's default `READ COMMITTED`, two transactions publishing overlapping rosters each read before the other has committed. Neither sees the other's publication, neither supersedes it, and both commit.
- Two Schedulers publishing at once is one way to get there. Two swap approvals against one roster is another, because each approval publishes its replacement through the same `publish` (ADR-022). Filed as #49.

**A version column would not stop it (#33).** It rejects the second of two writes to the same row. When no roster was published for those days before, the two transactions write different rows and share nothing a version could compare.

**ADR-004 considered exactly this** and rejected an `EXCLUDE` constraint for "one published roster per overlapping period". It wanted a readable first schema, and it was wary of a deferrable-constraint subtlety inside `publish`. Its trigger was "when either invariant is found violated in stored data". #49 is how it would come to be violated, found before it was.

## Decision

**We make two overlapping `PUBLISHED` rosters of one organisation unrepresentable.** Migration `V2__one_published_roster_per_moment.sql` adds:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE roster ADD CONSTRAINT roster_one_published_per_moment
    EXCLUDE USING gist (organisation_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
    WHERE (status = 'PUBLISHED');
```

- **Half-open, like `TimeRange`.** `tstzrange` defaults to `[)`, so consecutive rosters that only touch are allowed.
- **Only published rows.** Superseded rosters are history and may overlap; drafts bind nobody.
- **Scoped by organisation**, as every rule in this schema is. `btree_gist` supplies the `uuid` equality a GiST index needs.

**The adapter turns the refusal into the domain's word for it.** `JdbcRosterRepository.save` recognises the two ways PostgreSQL reports this collision, by SQLState rather than by exception type. Spring 7.0.9 maps `23P01` to no specific exception, so the type would say nothing.

- `23P01` (`exclusion_violation`) is one of them.
- `40P01` (`deadlock_detected`) is the other. Two writers each inserting an overlapping published row can each wait on the other, and PostgreSQL then aborts one.

Either becomes `RosterPublicationConflictException`, which the web adapter answers with **409**. It never becomes a 500.

**Publication still supersedes first.** The constraint is checked per statement, not deferred. Within one transaction `publish` marks the old roster superseded before it marks the new one published, so the constraint never sees both at once. The subtlety ADR-004 was wary of does not arise, and nothing is made `DEFERRABLE`.

**ADR-004's `EXCLUDE` alternative is reversed only for published rosters.** Overlapping unavailability is still enforced by the domain alone. ADR-004 itself is not rewritten.

## Consequences

**Easier**

- **Every writer is held to R11.** That includes one written later that forgets how publication is meant to work. Taking turns (ADR-027) keeps this from being reached through today's use cases; this is what stands behind it.
- **A lost race is visible.** The losing writer is refused, its transaction rolls back, and a swap it was approving stays pending. It is no longer silently published beside the winner.

**Harder**

- **The database needs `btree_gist`.** The extension ships with PostgreSQL and is marked trusted: a role with `CREATE` on the database can install it without superuser (PostgreSQL 18 documentation). A deployment whose migration role lacks that privilege fails at this migration, not later.
- **A database that already holds two overlapping published rosters fails the migration.** That is deliberate: which one is authoritative is not something a migration can decide. Mark the wrong one `SUPERSEDED` by hand, then migrate.
- **A deadlock loser waits before it is refused.** PostgreSQL resolves a deadlock only after `deadlock_timeout` (one second by default), so the refused writer answers late.
- **A new kind of index to keep in mind.** Every write that makes a roster `PUBLISHED` now costs a GiST index check. It is small beside what publication already reads.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Keep R11 in the application only** | What ADR-004 chose. Rejected because it holds only while writers take turns, and nothing in the schema says so. #49 is the proof that the application alone does not see concurrent writers. |
| **A trigger that checks for overlap before update** | No extension needed. Rejected: a trigger that queries its own table does not see other transactions' uncommitted rows, so two writers race past it exactly as they race past `publish`. Making it safe needs a lock, which is ADR-027's job, not a trigger's. |
| **A deferrable exclusion constraint** | Would let `publish` save rows in any order. Rejected as unnecessary, because supersede already precedes publish, and it would move the refusal to commit time, after the audit entries were written. |
| **A version column on `roster` (#33)** | Solves a different problem: two writes to one row. Rejected here because two overlapping publications write two different rows. #33 remains open for draft edits. |

**Revisit when** roster history must be append-only (Q24), which would change what "published" means in a row, or when a second database engine must be supported, since `EXCLUDE` is PostgreSQL's alone.
