-- An assignment has at most one pending swap request (R32, #94), whoever it asks. See ADR-039.
--
-- Raising a request checks for a pending one under the publication lock. This index is the backstop
-- for a writer that skipped the lock, as roster_one_published_per_moment is for R11. It is partial,
-- so a request leaves it the moment it is decided or lapses, and the assignment can be asked for
-- again. The assignment is the roster, the shift and the person on it, so the same shift on a
-- replacement roster is another assignment. Its leading columns also answer what a publication
-- lapses: the requests still pending on a roster it supersedes (R33).
--
-- V1 said a unique index the domain does not enforce is a new domain rule smuggled in as a storage
-- detail, and listed repeated swap requests among the untidy things the model permitted. The domain
-- now forbids two pending requests for one assignment, so this index mirrors a rule rather than
-- smuggling one in. A migration that has run cannot be edited, so V1 is corrected here.

-- Requests already stored may break the rule. Of several pending for one assignment, the oldest keeps
-- waiting, because that is the order a Scheduler works through the queue (ADR-004), with the
-- identity breaking a tie as it does there. The rest lapse: nobody decided them, and LAPSED is the
-- only final state a Scheduler did not choose. Nobody is told, as in V7.
WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY organisation_id, roster_id, shift_id, requester_id
                              ORDER BY requested_at, id) AS place
    FROM swap_request
    WHERE status = 'REQUESTED'
)
UPDATE swap_request
SET status = 'LAPSED'
FROM ranked
WHERE swap_request.id = ranked.id
  AND ranked.place > 1;

CREATE UNIQUE INDEX swap_request_one_pending_per_assignment
    ON swap_request (organisation_id, roster_id, shift_id, requester_id)
    WHERE status = 'REQUESTED';
