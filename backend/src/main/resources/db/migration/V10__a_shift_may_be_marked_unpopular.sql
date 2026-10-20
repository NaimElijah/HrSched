-- A Scheduler may mark a shift as unpopular, and fairness counts the unpopular shifts each person
-- carries. See ADR-010.
--
-- Nobody marked a shift stored before this, so each one is stored as not unpopular. That is what
-- was said about it, not a guess about how people feel. The default exists only to fill those rows
-- and is dropped at once, as in V5, so no later insert can leave the mark to the schema.

ALTER TABLE shift ADD COLUMN unpopular boolean NOT NULL DEFAULT false;
ALTER TABLE shift ALTER COLUMN unpopular DROP DEFAULT;
