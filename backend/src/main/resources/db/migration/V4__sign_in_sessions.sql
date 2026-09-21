-- Sessions, and the refresh tokens that renew them. See ADR-011 and ADR-028.
--
-- A session is the span between somebody signing in and that session ending, and it is what gets
-- revoked. Access tokens are not stored: every one of them names its session, so ending the session
-- ends every token issued for it without this schema ever seeing a token. That is the whole reason
-- the session is a row at all.
--
-- Two ways a session stops. expires_at is its absolute end, fixed when it starts and never extended
-- however active somebody is -- an attacker holding a stolen refresh token must not be able to keep
-- a session alive indefinitely by using it. revoked_at is somebody or something ending it early,
-- and revocation says which, so a security question ("how many sessions did reuse detection kill
-- last week?") is a query rather than a guess. The two columns are set together or not at all.
--
-- Not named "session": it is a reserved word in enough dialects, and the noun on its own says less
-- than the pair of words does.
--
-- A refresh token is stored as a SHA-256 digest, never as itself. A database that leaks must not
-- hand over working credentials; whoever presents a token supplies the only copy there is, and this
-- side only ever recomputes the digest to look it up. bytea rather than text, because a digest is
-- bytes and encoding it would invite two encodings.
--
-- Rows are kept rather than deleted when replaced, because a token presented after being replaced
-- is the signal that one of the two holders is not its owner, and a deleted row cannot say
-- anything. That the table therefore grows without limit is Q23.
--
-- No policy number appears here. How long a session may last, and how long a refresh token is good
-- for, are configuration (ADR-011); this table stores only the instants that follow from them.

CREATE TABLE sign_in_session (
    id         uuid        PRIMARY KEY,
    account_id uuid        NOT NULL REFERENCES account (id),
    started_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
        CONSTRAINT sign_in_session_ends_after_it_starts CHECK (expires_at > started_at),
    revoked_at timestamptz,
    revocation text
        CONSTRAINT sign_in_session_revocation_known CHECK (revocation IN (
            'SIGNED_OUT', 'REFRESH_REUSED', 'PASSWORD_CHANGED', 'ACCOUNT_DISABLED',
            'PASSWORD_RESET')),
    CONSTRAINT sign_in_session_revoked_with_a_reason
        CHECK ((revoked_at IS NULL) = (revocation IS NULL))
);

-- Every read of a session but one starts from its account: ending every session of somebody whose
-- password changed, or whose account was disabled.
CREATE INDEX sign_in_session_by_account ON sign_in_session (account_id);

CREATE TABLE refresh_token (
    digest     bytea       PRIMARY KEY,
    session_id uuid        NOT NULL REFERENCES sign_in_session (id) ON DELETE CASCADE,
    issued_at  timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    replaced_at timestamptz
);

-- Revoking a session has to reach every token of it, including the ones already replaced.
CREATE INDEX refresh_token_by_session ON refresh_token (session_id);
