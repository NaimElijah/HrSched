-- Exactly one roster is authoritative for any given moment (R11), in the schema. See ADR-026.
--
-- publish supersedes every overlapping published roster and then publishes its own, all in one
-- transaction, and that is enough while publications take turns. Two that do not - each reading
-- before the other commits - would otherwise both commit, and two rosters would govern the same
-- days. This makes that state unstorable, so the second writer is refused instead.
--
-- Only PUBLISHED rows are constrained: a superseded roster is history, and history may overlap; a
-- draft binds nobody. tstzrange is half-open by default, like TimeRange, so rosters that only touch
-- do not conflict. btree_gist supplies the uuid equality a GiST index needs; it ships with
-- PostgreSQL and is a trusted extension, so the migrating role needs CREATE on the database rather
-- than superuser.
--
-- A database that already holds two overlapping published rosters fails here, deliberately: which
-- of them is authoritative is not something a migration can decide. Supersede the wrong one by hand
-- and migrate again.

CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE roster ADD CONSTRAINT roster_one_published_per_moment
    EXCLUDE USING gist (organisation_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
    WHERE (status = 'PUBLISHED');
