-- An inbox lists the newest first, and of notifications raised at one instant, the last told first.
--
-- One use case can tell somebody several things at the same instant: a publication approving a swap
-- tells of the lapses, then of the approval, so that the approval tops the inbox (ADR-052). The
-- inbox broke that tie by the notification's identity, a random UUID, so the approval landed
-- anywhere among them (#254). sequence records the order rows are inserted in from this migration
-- on, and the inbox breaks the tie on it. The domain never sees it: it is how this table remembers
-- an order, not a fact about what somebody was told.
--
-- GENERATED ALWAYS, so nothing writes one unless it says OVERRIDING SYSTEM VALUE: the order is the
-- database's to record. It is the order of insertion, not of commit; the tellings that tie come
-- from one transaction, so the two agree where it matters. It is not UNIQUE: whether paging the
-- inbox needs (occurred_at, sequence) to be a total order is #41's to decide.
--
-- Adding the column rewrites the table under an ACCESS EXCLUSIVE lock, numbering every row already
-- there, so the column is NOT NULL from the start. Nothing recorded the order those rows were told
-- in, so they are numbered in whatever order the rewrite reads them: a tie among them stays in an
-- order no truer than the identity order it replaces. Every row inserted afterwards is numbered
-- above all of them. The index is dropped first so that the rewrite does not rebuild it only for it
-- to be replaced; no reader sees the table without it, as the lock holds them until the commit.

DROP INDEX notification_by_inbox_newest_first;

ALTER TABLE notification ADD COLUMN sequence bigint GENERATED ALWAYS AS IDENTITY;

CREATE INDEX notification_by_inbox_newest_first
    ON notification (organisation_id, recipient_id, occurred_at DESC, sequence DESC);
