-- A refresh token expires after it is issued. See ADR-011 and ADR-028.
--
-- SessionService issues a token at the instant it signs somebody in or renews their session, and
-- has it expire at the earlier of two instants, each strictly later than that one: the end of the
-- idle lifetime, which SessionPolicy refuses unless it is positive, and the end of the session,
-- which a renewal is allowed to reach only while the session is live, strictly before it expires.
-- Both instants come from the one clock reading, so no other clock can put them out of order. A
-- token that expires at or before its issue is therefore no state the domain makes, and
-- refresh_token_expires_after_it_is_issued refuses one from a writer that skipped it, as
-- sign_in_session_ends_after_it_starts does for a session (V4). Nothing is rewritten: every stored
-- token was written by that code, and a row breaking the rule fails this migration loudly rather
-- than being given an expiry nobody chose.
--
-- No CHECK orders a session's revoked_at after its started_at, deliberately. SignInSession holds no
-- such rule, and the two instants come from different clocks: started_at from the instance that
-- served the sign-in, revoked_at from whichever serves the revocation, or from the database's own
-- clock in a migration such as V9. Wall clocks are neither monotonic nor exactly agreed, so a
-- revocation moments after a sign-in can be stamped a little before it. A CHECK would then refuse
-- the revocation and roll back the disable, the reset or the password change it belongs to,
-- failing a security act over the order of two readings of different clocks. As V8 says of V1, a
-- constraint the domain does not enforce is a domain rule smuggled in as a storage detail.
--
-- V3 is corrected here, because a migration that has run cannot be edited. It calls the operator's
-- account the one exception to "organisation_id on every aggregate root". It is not the only one:
-- sign_in_session and refresh_token carry no organisation either, because a session belongs to an
-- account rather than to a tenant, and the operator's sessions belong to none (ADR-055).

ALTER TABLE refresh_token
    ADD CONSTRAINT refresh_token_expires_after_it_is_issued CHECK (expires_at > issued_at);
