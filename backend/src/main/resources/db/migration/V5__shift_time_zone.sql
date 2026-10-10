-- Each shift's working-time days are counted in its own zone (R36). See ADR-034.
--
-- The zone is an IANA time zone database name, such as Europe/London, validated where a shift is
-- defined; the database does not check it, because it does not hold the same zone database.
--
-- Every shift stored before this was judged in UTC, so UTC is what they keep: a shift whose days
-- were counted one way does not have them counted differently after the fact. Shifts have no edit
-- path, so that backfill is permanent. The default exists only to fill those rows and is dropped at
-- once, so no later insert can fall back on a zone nobody chose.

ALTER TABLE shift ADD COLUMN zone text NOT NULL DEFAULT 'UTC';
ALTER TABLE shift ALTER COLUMN zone DROP DEFAULT;
