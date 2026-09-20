# ADR-011: Issue our own signed access tokens, and resolve the actor from the stored account and session on every request

- **Status:** Accepted
- **Date:** 2026-09-16

## Context

HrSched has no authentication. The acting identity comes from four request headers
(`X-Organisation`, `X-User`, `X-Role`, `X-Employee`), parsed and believed by four copies of
`ActorFromHeaders`. Any caller can claim any role in any organisation just by sending different
headers. `requirements.md` calls this a placeholder to be deleted when the identity provider is
decided, and reserves this number for that decision — Q12.

ADR-020 closed one part of the gap: `X-Organisation` must now name an organisation somebody
actually onboarded. That is not authentication, and it does not make the placeholder safe. It
establishes that a claimed tenant is real, not that the caller belongs to it — anyone who learns a
real organisation identifier can still claim any role inside it, including Admin.

A second hole sits behind the same placeholder. Every service guards with
`if (actor instanceof TenantActor t) t.requireRoleIn(...)`, an idiom that silently *skips* an
`Operator` rather than refusing one, so the platform Operator — meant to administer the register of
organisations and nothing else — can currently read and change data inside any tenant. Deciding who
the caller is has to come before deciding what an Operator may do, so this ADR is the one that has
to land first.

## Decision

**HrSched issues its own signed access tokens, and Spring Security's resource-server support
verifies them (D1). No third-party dependency is added beyond Spring Security itself** — no
external identity provider, no authorisation server, nothing that would introduce a second
component this deployment has to run and keep available.

- **Access token:** RS256, with the algorithm pinned in the decoder rather than trusted from the
  token's own header, and `typ: at+jwt`. Claims are `iss`, `aud`, `sub` (the account's `UserId`),
  `iat`, `exp` and `sid` (the sign-in session id); `exp`, `iat` and `sid` are required by explicit
  validators, not merely accepted if present. Lifetime is configuration, not a constant.
- **Signing key:** a PEM supplied by the environment. Startup fails without one unless
  `hrsched.security.token.ephemeral=true`, a flag set only by `spring-boot:run`'s plugin
  configuration and by the test configuration — never by a default that would let a real deployment
  start on a key nobody chose. The ephemeral key is generated with the JDK's own
  `KeyPairGenerator`, so nothing beyond the JDK and Spring Security is needed to run locally either.
- **Every request is resolved against stored state, not trusted from the token's claims.**
  `ActorAuthenticationConverter` asks access's published `AuthenticatedActorsUseCase.findActor(UserId, sessionId)`
  — one read-only query joining the account and the session the token names. A missing or disabled
  account, or a revoked or expired session, is a 401, regardless of whether the token itself still
  verifies. This is the second half of the decision, and it is what makes a role change, a
  disabling, a sign-out or a password change bite on the very next request rather than only once
  the token expires: the token is proof of *who signed in*, and the stored session is proof that
  nothing has since made that no longer true. A database error here fails closed with a 500 — an
  actor this cannot verify is never treated as one it has.
- **Principal and authorities.** The principal is the kernel `Actor`. Authorities are
  `ROLE_<role>` or `ROLE_OPERATOR`, or, while a temporary password is pending, *only*
  `PASSWORD_CHANGE_REQUIRED` — an account mid-forced-change can reach nothing else.
- **Refresh token:** 256 random bits, base64url-encoded, stored only as a SHA-256 digest — the
  database never holds a token it could hand back. It rotates on every use, and presenting a token
  that has already been replaced revokes the whole session outright, which is what makes reuse
  detectable rather than merely logged. It travels in a `HttpOnly`, `Secure`, `SameSite=Strict`
  cookie scoped to `/api/auth`, so no script on the page can read it and no other path receives it.
- **Password hashing** goes through Spring Security's `DelegatingPasswordEncoder` (bcrypt), so the
  algorithm can change later without migrating stored hashes, and bcrypt strength is configuration,
  not a constant, for the same reason no legal threshold in this codebase is one. Argon2 would need
  BouncyCastle, a new dependency this decision does not introduce (D1); PBKDF2 is the fallback if
  bcrypt's 72-byte password limit ever proves to be a real problem rather than a documented one.
- **Refusals that must leave a trace are returned values, not thrown exceptions** — a deliberate
  departure from ADR-023's "a refusal is an unchecked exception". `TransactionalUseCases` rolls
  back the transaction on any unchecked exception, which is exactly right for a refusal that must
  undo what it started, but exactly wrong for a failed sign-in count or a reuse revocation: both
  *are* the record of what happened, and rolling them back would erase the evidence of the attempt
  they exist to count. `signIn` and `refresh` therefore return sealed `SignInOutcome` /
  `RefreshOutcome` values, and the web adapter is what turns a refusal outcome into a 401.
- **Sign-in never distinguishes its refusal reason.** An unknown sign-in name, a wrong password, a
  disabled account and a locked account all answer an identical 401, and a password comparison runs
  unconditionally — an unknown name is compared against a dummy hash computed at startup, so the
  response time does not itself tell an attacker which case they hit. A run of consecutive failures
  locks the account for a period; both the run length and the lockout period are configuration.
  Neither the submitted name, the password, nor any issued token is ever logged.

## Consequences

**Easier**

- The acting identity is no longer whatever a caller typed into a header. A request now carries
  proof of a real sign-in, verified cryptographically and then checked against stored state that
  can be revoked at any time.
- Disabling an account, changing its role, signing it out, or forcing a password change all take
  effect on the *next* request, not only once a long-lived token happens to expire — because the
  chain a token starts is only ever half of the check; the other half is the database.
- Cryptography stays inside a library built for it. Nothing here hand-rolls token verification,
  signature checking, or password hashing; Spring Security and Nimbus JOSE+JWT do, which is exactly
  the kind of code this project's own conventions say nobody should write themselves.
- It gives the Operator-bypass hole an identity to be confined *from* — deciding who a caller
  provably is has to exist before a rule can refuse what an Operator, specifically, may not do.

**Harder**

- **HrSched now stores password hashes it did not store before, which makes the account table a
  breach target it was not.** bcrypt and a configured strength make that expensive to exploit if
  the table ever leaks, but "expensive" is not "safe", and this is a cost the header placeholder
  never carried.
- **Lockout is a denial-of-service lever.** Locking an account after a run of failures stops a
  guesser, but it equally lets anyone who knows a sign-in name lock the account it belongs to, and
  there is no IP-based rate limiting to blunt that. This is recorded rather than fixed here; it is
  an open question the requirements already carry.
- **A returned refusal is a second shape for "no" in a codebase that otherwise throws it.**
  ADR-023 made an unchecked exception the one way a use case says no, specifically so
  `TransactionalUseCases` could roll back on it without every service repeating that decision. Sign-
  in and refresh now carry an explicit second shape, and every future reader of a use case has to
  know which shape theirs is rather than assume the one ADR-023 describes.
- **Verifying a token is no longer free of the database.** Every authenticated request now costs a
  read-only query in addition to whatever the request would have needed anyway. This is a
  deliberate trade for a token that can be revoked in fact rather than only in principle; if it ever
  shows up as a measured latency problem, the fix is not visible from this decision alone.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **An external OIDC identity provider (e.g. Keycloak)** | Correct once more than one thing has to trust the same identity, but today there is one API and one SPA, and it would mean running and operating a second service for a problem this size does not yet have. **Revisit when** a customer requires SSO, SAML or MFA, or a second service needs to verify the same tokens. |
| **HS256 with a shared secret** | Simpler than a key pair for a single process, and genuinely the simpler option of the two. Kept as RS256 instead because a verify-only public key is exactly what a second verifier would need and a shared secret is not — handing out an HS256 secret to a second service hands out the power to *sign* tokens, not just check them. **Revisit if** key-pair custody (generating, storing and rotating the PEM) proves to be a real operational burden rather than a one-time setup cost. |
| **Roles carried as token claims** | Would remove the per-request database read this decision keeps. Rejected because a role held only in the token cannot change until the token expires: disabling an account or demoting its role would not bite until the old token ran out, which is exactly the delay this decision exists to close. **Revisit if** the per-request account/session lookup shows up as a measured latency problem. |
| **Opaque tokens with introspection** | Trades a self-contained, cheaply-verified JWT for a random string that must be resolved through an introspection call on every request — which, with no separate authorisation server, would mean hand-writing an `OpaqueTokenIntrospector` that does no more than `AuthenticatedActorsUseCase` already does, for no benefit over the chosen design, since that lookup happens either way. **Revisit if** HrSched ever grows a real authorisation server for an unrelated reason (third-party API clients, say), at which point introspection stops being a bespoke component and starts being reuse of one that already exists. |
| **Server-side HTTP sessions (`JSESSIONID`)** | Simpler for a same-origin SPA, and would need no token at all. Rejected because the owner chose bearer tokens (D1) over a session cookie as the credential itself. **Revisit if** the API gains a client that is not a browser, which a session cookie serves badly and a bearer token does not. |
| **Row-level security in PostgreSQL, deferred again** | ADR-004 named "authentication lands" as the trigger to revisit this, and it has now fired. Rejected once more anyway: the organisation now comes from a verified account rather than a claimed header, which raises what the application-layer check is worth, but the session-variable plumbing an RLS policy would need, and the exemptions the Operator and Flyway would both require, still cost more than the defence-in-depth they would buy. **New trigger:** the first code review that finds a query missing its tenant predicate, or the day a second service starts writing this database directly. |
| **A logging-context filter carrying the user and organisation, deferred again** | ADR-016 named the arrival of a real identity as its own trigger, and that has also fired: the identity is no longer four headers taken on faith. Rejected once more because the audit record and the refusal logs already carry both an actor and an organisation where it matters, and a filter that stamped every log line with them would duplicate that for no reader this project has yet. **New trigger:** the first production incident where a log line could not be tied back to the tenant or the user it belonged to. |
