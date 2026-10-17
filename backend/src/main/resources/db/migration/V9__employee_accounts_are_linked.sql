-- An account holding the Employee role is always linked to an employee of its own organisation, and
-- one that is not is disabled until it is linked (R34, Q32, #97).
--
-- The link is what an Employee account is for: self-service answers "is this me?" through it
-- (ADR-012), so an Employee linked to nobody signs in to find nothing of theirs. Until now one could
-- be created, and the ones already stored cannot be linked here: whom each was meant for is known
-- only to the Admin who made it, and a backfill invents nothing. They are disabled instead. An
-- Admin links one and then restores it; linking alone does not, so an account disabled for some
-- other reason is never quietly turned back on.
--
-- Disabling an account ends every session it holds (R17), so their live sessions are revoked first,
-- while "active" still says which accounts are meant. As in V6, the moment comes from the
-- database's clock, and as in V7, nobody is told and nothing is audited: a migration has no caller
-- to act on behalf of. Only Employee accounts change, so every organisation keeps the Admins it had
-- (R14).

UPDATE sign_in_session
SET revoked_at = transaction_timestamp(),
    revocation = 'ACCOUNT_DISABLED'
WHERE revoked_at IS NULL
  AND account_id IN (SELECT id
                     FROM account
                     WHERE role = 'EMPLOYEE'
                       AND employee_id IS NULL
                       AND active);

UPDATE account
SET active = false
WHERE role = 'EMPLOYEE'
  AND employee_id IS NULL
  AND active;

-- From here the schema refuses what the domain refuses, for a writer that skips it: an Employee
-- account that may be used while it is linked to nobody.
ALTER TABLE account
    ADD CONSTRAINT account_employee_role_is_linked
        CHECK (role IS DISTINCT FROM 'EMPLOYEE' OR employee_id IS NOT NULL OR NOT active);
