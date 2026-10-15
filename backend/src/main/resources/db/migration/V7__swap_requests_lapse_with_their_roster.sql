-- A pending swap request lapses when the roster it names is superseded, and its parties are told
-- (R33, #94). See ADR-039.
--
-- LAPSED is a final state of its own. Nobody decided the request: the roster it named was replaced
-- before anybody did, so there is nothing left to approve. The publication that replaced the roster
-- lapses it and tells both parties with a SWAP_LAPSED notification, so both checks widen.

ALTER TABLE swap_request DROP CONSTRAINT swap_request_status_known;
ALTER TABLE swap_request
    ADD CONSTRAINT swap_request_status_known
        CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'LAPSED'));

ALTER TABLE notification DROP CONSTRAINT notification_kind_known;
ALTER TABLE notification
    ADD CONSTRAINT notification_kind_known
        CHECK (kind IN ('SWAP_REQUESTED', 'SWAP_APPROVED', 'SWAP_REJECTED', 'SWAP_LAPSED'));

-- Requests still pending against a roster that has already been superseded lapse now, as they would
-- have done had the rule existed when the roster was replaced. Nobody is told: a migration has no
-- caller to tell anybody on behalf of, and writing a notification here would record a telling that
-- never happened. Only a request still waiting lapses; a decision already made stays as made.
UPDATE swap_request
SET status = 'LAPSED'
FROM roster
WHERE swap_request.status = 'REQUESTED'
  AND roster.id = swap_request.roster_id
  AND roster.organisation_id = swap_request.organisation_id
  AND roster.superseded_at IS NOT NULL;
