# ADR-037: Keep roster history as a record of when each roster governed, written once

- **Status:** Accepted
  - **Amended 2026-10-31:** The adapter no longer turns every `23P01` into
    `RosterPublicationConflictException`: an exclusion violation is translated only when it names
    `roster_one_published_per_moment`, so one of another exclusion constraint surfaces as the store
    reports it. A deadlock, `40P01`, names no constraint and is still translated by its SQLSTATE
    ([#255](https://github.com/NaimElijah/HrSched/issues/255)).
- **Date:** 2026-10-14

## Context

Q24 was answered on 2026-09-22: roster history **must be append-only**, so that what was
authoritative at any past moment can be reconstructed exactly (R37, #106).

**Before this decision the past could be inferred, but not reconstructed.**

- Superseding rewrote `roster.status` in place, from `PUBLISHED` to `SUPERSEDED` (ADR-004 said so
  in as many words).
- No moment was stored anywhere: not on `Roster`, not in the `roster` table, and not in the audit
  record. `AuditedChange` carries no time, and the audit trail is a log stream, not a store
  (ADR-018).
- So the history said which rosters existed, but not which one governed a given day at a given
  past moment.

**The forces on any answer:**

- **R11 must keep its schema backstop.** ADR-026 made two overlapping published rosters of one
  organisation unstorable with an exclusion constraint over a single row. Whatever records history
  has to leave that constraint able to see what it guards.
- **Publications already take turns** within an organisation (ADR-027), and read the whole history
  under that lock (ADR-033).
- **The domain is pure, and time is an input.** A use case takes a `Clock`; it never asks the JVM
  or the database what time it is (V1's comment on `notification.occurred_at`).
- **A race was found while designing this.** `assign` and `unassign` take no lock. An edit of a
  draft that committed after that draft was published wrote `status = 'DRAFT'` back. That
  un-published the roster with no audit entry, and left the roster it had superseded superseded,
  so nothing governed those days.
- **The rows already stored carry no time at all.** No record of when they were published or
  superseded was ever kept.

## Decision

**We record two moments on each roster, each written once, and store no status.**

- `published_at` is written when a draft is published.
- `superseded_at` is written when a newer roster supersedes it, from `NULL`, and nothing else in
  the row changes.
- The status follows from the two: no `published_at` is a draft; no `superseded_at` is published;
  both is superseded. A stored status could disagree with the moments, and now there is none.
- A roster governed its period over the half-open record interval
  `[published_at, superseded_at)`.

**Reconstruction is that interval read back.** At moment of record `t`, the roster that governed a
scheduled instant `x` is the one whose period holds `x` and whose
`published_at ≤ t < coalesce(superseded_at, ∞)`.

- There is at most one, by the constraint below.
- No production code computes it, because nothing asks yet. `JdbcRosterRepositoryTest` states it
  once, as `authoritativeAt`, and uses it to prove R37.

**R11 holds for the past as well as the present.** Migration `V6__append_only_roster_history.sql`
replaces ADR-026's constraint, keeping its name:

```sql
ALTER TABLE roster ADD CONSTRAINT roster_one_published_per_moment
    EXCLUDE USING gist (organisation_id WITH =,
                        tstzrange(starts_at, ends_at) WITH &&,
                        tstzrange(published_at, superseded_at) WITH &&)
    WHERE (published_at IS NOT NULL);
```

At every moment of record, at most one roster of an organisation governs any moment of its
schedule. `publish` still supersedes before it publishes. The old roster's interval closes at
exactly the moment the new one's opens, so the two only touch.

**Each publication takes one moment, after the lock, that moves record time forward.**
`SchedulingService.publish` reads its `Clock` once, after `lockPublicationsOf`, and passes the
reading to `Roster.nextMomentOfRecord`. The result is used for every roster that publication
supersedes and for the publication itself. The rule:

- The moment is the clock's reading, truncated to the microsecond.
- If the organisation's history already reaches that reading, the moment is one microsecond after
  the latest moment recorded instead. A clock that is fixed, coarse or stepped back would otherwise
  end a roster's authority before it began, or begin and end it at one instant, which records that
  it never governed.
- History is kept to the microsecond because PostgreSQL's `timestamptz` keeps no finer. `Roster`
  refuses a finer moment rather than letting the driver round it.

**`JdbcRosterRepository.save` records transitions; it does not overwrite rows.**

- A draft, and a draft's publication, are written by one upsert, guarded by
  `roster.published_at IS NULL`.
- A supersession sets `superseded_at`, guarded by `published_at IS NOT NULL AND superseded_at IS NULL`,
  and touches nothing else.
- A guard that stops a write is reported as `RosterNotEditableException`. That is the same 400
  `Invalid request` as editing a published roster through the domain, so the race above is now
  refused.

**The rows already stored are given the moment history began to be kept.** V6 gives every
published and superseded row `published_at = transaction_timestamp()`.

- A superseded row also gets `superseded_at` equal to that moment. The resulting empty interval
  claims it governed at no recorded moment.
- This is the one place a moment comes from the database's clock: a migration has no caller.
- Because each live publication moves record time forward, only these rows can ever carry an empty
  interval.

**What this does to earlier decisions:**

- **ADR-026 is superseded by this record.** Its constraint is replaced by the one above. The rest of
  it still holds:
  - the adapter turning `23P01` and `40P01` into `RosterPublicationConflictException` and a 409;
  - supersede-before-publish;
  - `btree_gist`.
- **ADR-027's revisit trigger ("when roster history becomes append-only") has fired, and the lock
  stands.** Three things still need it:
  - R35 has no schema backstop (ADR-033);
  - the constraint alone would refuse the later of two simultaneous publications;
  - the moment rule reads the history it moves past, which is only sound if no other publication
    writes in between.
- **ADR-004's statement that roster history "is not append-only" no longer describes the system.**
  ADR-004 is not rewritten; this record overturns that one line of it.

## Consequences

**Easier**

- **R37 holds.** Which roster governed any scheduled instant can be answered for any moment of
  record from V6 on, and the schema refuses a history in which the answer would be two rosters.
- **The silent un-publish is gone.** A stale draft write over a published roster is refused instead
  of applied.
- **Status and history cannot disagree,** because only the history is stored.

**Harder**

- **Append-only is held by the adapter and by the constraint, not by the database against every
  writer.** A hand-written `UPDATE` could still rewrite `published_at`. Every writer today goes
  through `JdbcRosterRepository`, which is why there is no trigger yet; see the revisit trigger
  below.
- **History before V6 cannot be reconstructed.** It was never recorded. The migration states the
  moment reconstruction becomes exact rather than inventing earlier ones.
- **Record time can run ahead of the wall clock.** If the clock reads behind what is already
  recorded, each publication lands one microsecond after the last until the clock catches up. That
  only happens after the clock steps back, and the order of events stays right, which is what
  reconstruction needs.
- **A refused stale edit answers 400, not 409.** It shares `RosterNotEditableException` with an
  ordinary edit of a published roster, which is what it is. Telling "somebody published this while
  you edited it" apart belongs to #33, which is about stale writes.
- **An edit that commits before the publication writes is still a lost update.** Publication
  stores the assignments it validated over the edit. That is also #33.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **An insert-only `roster_supersession` table**, leaving published roster rows untouched | The most literal "append-only": no row is ever updated. Rejected because the exclusion constraint cannot span two tables, so R11's schema backstop would be lost, or moved into a trigger that ADR-026 already rejected because concurrent writers race past it. |
| **A status-change log beside a status still rewritten in place** | Two sources of truth that can drift apart, and the row is still rewritten. |
| **Keep the `status` column and add the two moments** | The status would duplicate the moments, could disagree with them, and superseding would still rewrite it. |
| **Constrain the present only** (`WHERE published_at IS NOT NULL AND superseded_at IS NULL`) | Keeps R11 exactly as ADR-026 had it. Rejected because it lets history record two rosters governing one moment, which makes reconstruction ambiguous; the three-column constraint costs nothing more. |
| **A trigger that freezes a published row except for one `superseded_at` write** | Would hold append-only against every writer. Rejected for now because every writer goes through the guarded adapter, and the simplest thing that meets R37 is enough. It would also have to guard `roster_assignment`. |
| **Take moments from the database** (`now()`, `clock_timestamp()`) | The codebase takes time from the caller's clock, so tests supply it. `transaction_timestamp()` is also taken before the lock wait, so it could predate the publication the lock made it wait for. |
| **Invent moments for the stored rows**, such as `-infinity` | Claims something nobody recorded, and an infinite timestamp has no faithful `Instant`. |

**Revisit when:**

- a second writer reaches the roster tables, such as an operations script, another service or a
  data-fixing migration: add the trigger above;
- an Admin must read roster history: the reconstruction rule becomes a query on the port;
- a second database engine must be supported, since `EXCLUDE` is PostgreSQL's alone, as ADR-026
  already noted.
