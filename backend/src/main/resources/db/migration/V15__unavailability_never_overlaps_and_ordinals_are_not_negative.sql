-- An employee's declared unavailability never overlaps (R7), in the schema. See ADR-004.
--
-- Employee.unavailableFor refuses a span overlapping one already declared, so nothing the adapter
-- writes breaks R7; this makes a row written past it -- by hand, by a restore, by a bug -- unstorable
-- as well, as roster_one_published_per_moment does for R11. V1 left the rule to the domain alone
-- and said, of this table, that the overlap is "deliberately NOT enforced here" and that an EXCLUDE
-- "would need btree_gist and is recorded as a rejection in ADR-004". Both are now false. ADR-004
-- said to revisit when btree_gist was wanted for another reason, and V2 installed it for the
-- published-roster exclusion. A migration that has run cannot be edited, so V1 is corrected here.
--
-- tstzrange is half-open by default, [starts_at, ends_at), like TimeRange: a span beginning where
-- another ends shares no instant with it, so spans that only abut both store, as R7 allows. A span
-- declared beyond the microsecond is stored rounded to it (ADR-040), and rounding never reverses
-- two instants, so spans the domain holds apart are never stored overlapping; spans less than a
-- microsecond apart may be stored abutting, which R7 allows.
--
-- The check is immediate rather than deferred. JdbcEmployeeRepository saves an employee by deleting
-- their rows and inserting the new set, and each statement sees the ones before it, so a new set
-- overlapping the old one is never compared with it. Two saves of one employee do not interleave:
-- each change reads the employee for update first, so the second waits for the first to commit
-- (ADR-041). No adapter translates a violation: the domain refuses an overlap before anything is
-- written, so one reaching this is a defect, and it surfaces.
--
-- A database already holding an overlap fails here, deliberately: which of the spans a person meant
-- is not something a migration can decide, and the domain already refuses to load that person.
-- Correct their rows by hand and migrate again.
--
-- The exclusion builds its index under an ACCESS EXCLUSIVE lock, so every read of the table waits
-- while it builds, and it has no non-blocking form: an EXCLUDE cannot be added NOT VALID. That is
-- accepted while nothing is deployed; how a migration may lock a live database is #160's to decide.
--
-- The ordinal of both owned tables keeps the aggregate's list order and nothing else (V1). The
-- adapter writes list positions, counted from 0, so a negative one is a row nothing of ours wrote;
-- the checks say so.

ALTER TABLE employee_unavailability ADD CONSTRAINT employee_unavailability_does_not_overlap
    EXCLUDE USING gist (employee_id WITH =, tstzrange(starts_at, ends_at) WITH &&);

ALTER TABLE employee_unavailability ADD CONSTRAINT employee_unavailability_ordinal_not_negative
    CHECK (ordinal >= 0);

ALTER TABLE employee_preference ADD CONSTRAINT employee_preference_ordinal_not_negative
    CHECK (ordinal >= 0);
