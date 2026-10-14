-- Roster history is append-only (R37, Q24): what was authoritative at any past moment can be
-- reconstructed exactly. See ADR-037.
--
-- A roster used to carry a status that superseding rewrote in place, so the past could be inferred
-- but not reconstructed: nothing said when a roster began or stopped governing. It now records
-- both moments instead. published_at is written once, when a draft is published. superseded_at is
-- written once, from NULL, when a newer roster supersedes it, and nothing else in the row changes.
-- The status is no longer stored: it follows from the two moments, so it cannot disagree with them.
--
-- A roster governed its days from published_at until superseded_at, half-open. The constraint V2
-- added is replaced by one over those intervals too: at every moment of record, at most one roster
-- of an organisation governs any moment of its schedule. That is R11, which V2 enforced for the
-- present only, now held for the past as well. V2's own comment, that history may overlap, no
-- longer holds; a migration that has run cannot be edited, so it is corrected here.
--
-- The rows already stored carry no record of when anything happened, because none was kept. They
-- are given the moment this migration runs, which is when history began to be kept, rather than an
-- invented past: a published roster governs from then, and a superseded one is given an empty
-- interval, which claims it governed at no recorded moment. Reconstruction is exact from here on.
-- This is the one place a moment comes from the database's clock rather than the caller's: a
-- migration has no caller. Every live publication is recorded strictly after the moments already
-- stored for its organisation, so only these rows can ever carry an empty interval.

ALTER TABLE roster DROP CONSTRAINT roster_one_published_per_moment;

ALTER TABLE roster
    ADD COLUMN published_at  timestamptz,
    ADD COLUMN superseded_at timestamptz;

UPDATE roster SET published_at = transaction_timestamp()
    WHERE status IN ('PUBLISHED', 'SUPERSEDED');
UPDATE roster SET superseded_at = published_at WHERE status = 'SUPERSEDED';

-- Named before the column goes, so that what replaces each check is visible; dropping the column
-- would take roster_status_known and this one with it.
ALTER TABLE roster DROP CONSTRAINT roster_version_iff_not_draft;
ALTER TABLE roster DROP COLUMN status;

ALTER TABLE roster
    ADD CONSTRAINT roster_version_iff_published
        CHECK ((published_at IS NULL) = (validated_against_constraint_version IS NULL)),
    ADD CONSTRAINT roster_superseded_only_once_published
        CHECK (superseded_at IS NULL
               OR (published_at IS NOT NULL AND superseded_at >= published_at)),
    ADD CONSTRAINT roster_one_published_per_moment
        EXCLUDE USING gist (organisation_id WITH =,
                            tstzrange(starts_at, ends_at) WITH &&,
                            tstzrange(published_at, superseded_at) WITH &&)
        WHERE (published_at IS NOT NULL);
