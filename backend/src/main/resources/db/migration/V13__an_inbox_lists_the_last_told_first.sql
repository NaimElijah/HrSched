-- An inbox lists the newest first, and of notifications raised at one instant, the last told first.
--
-- One use case can tell somebody several things at the same instant: a publication approving a swap
-- tells of the lapses, then of the approval, so that the approval tops the inbox (ADR-052). The
-- inbox broke that tie by the notification's identity, a random UUID, so the approval landed
-- anywhere among them (#254). sequence records the order rows were inserted, and the inbox breaks
-- the tie on it. The domain never sees it: it is how this table remembers an order, not a fact
-- about what somebody was told.
--
-- GENERATED ALWAYS, so no statement can write one: the order is the database's to record. It is
-- the order of insertion, not of commit; the tellings that tie come from one transaction, so the
-- two agree where it matters.
--
-- Adding the column rewrites the table under an ACCESS EXCLUSIVE lock, numbering every row already
-- there, so the column is NOT NULL from the start. Nothing recorded the order those rows were told
-- in, so they are numbered in whatever order the rewrite reads them: a tie among them stays in an
-- order no truer than the identity order it replaces. Every row inserted afterwards is numbered
-- above all of them.

ALTER TABLE notification ADD COLUMN sequence bigint GENERATED ALWAYS AS IDENTITY;

DROP INDEX notification_by_inbox_newest_first;
CREATE INDEX notification_by_inbox_newest_first
    ON notification (organisation_id, recipient_id, occurred_at DESC, sequence DESC);
