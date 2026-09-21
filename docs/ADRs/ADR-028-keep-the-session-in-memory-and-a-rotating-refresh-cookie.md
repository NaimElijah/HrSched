# ADR-028: Keep the browser session as an in-memory access token and a rotating refresh cookie

- **Status:** Accepted
- **Date:** 2026-09-16

## Context

[ADR-011](ADR-011-issue-our-own-access-tokens.md) decides that HrSched issues its own short-lived
access tokens. That leaves the question it deliberately did not answer: where a browser keeps them,
and how a session outlives a ten-minute token without becoming a ten-hour one.

Every option here is a trade between two failure modes, and there is no arrangement that avoids
both:

- **A credential a script can read** is a credential a cross-site scripting flaw hands to an
  attacker. Anything in `localStorage` is readable by any script on the page.
- **A credential the browser attaches by itself** is a credential another site can make the browser
  attach. That is what cross-site request forgery is.

The frontend is a single-page application served from the same origin as the API through a dev
proxy, and **no CORS configuration exists anywhere in this repository**. That last fact turns out to
be load-bearing rather than incidental.

## Decision

**We split the session in two, and give each half the protection that fits it (D4).**

- **The access token lives only in JavaScript memory.** Never in `localStorage`, never in
  `sessionStorage`, never in a cookie. It dies with the tab, which is correct: it is worth ten
  minutes, and the session is renewed rather than the token being kept.
- **The refresh token lives in a cookie the page cannot read**, named `__Secure-hrsched-refresh`,
  with `HttpOnly`, `Secure`, `SameSite=Strict` and `Path=/api/auth`. Each attribute answers a
  specific attack: `HttpOnly` means a script that can read the page still cannot walk away with the
  means to renew indefinitely; `Secure` keeps it off plain HTTP; `SameSite=Strict` stops it being
  attached to a cross-site request at all; `Path=/api/auth` keeps it out of every ordinary API
  request, so it cannot leak through one; and the `__Secure-` prefix makes a browser refuse to set
  the cookie unless it really is Secure, which stops a network attacker who can forge plain HTTP
  from overwriting it.
- **Renewing and signing out additionally require the header `X-Requested-With`**, and only its
  presence is checked. A browser will not attach a header like that to a cross-site request without
  asking permission first, and since this application configures no CORS, that permission is never
  granted. It is a second lock on the same door as `SameSite`, and it costs one line.
- **The refresh token is 256 random bits, stored as a SHA-256 digest.** It carries no structure and
  means nothing; it is recognised only by being found. A database that leaks therefore hands over
  nothing anybody can present.
- **It rotates on every use, and a token presented twice ends the whole session.** The exchange is a
  conditional update — `SET replaced_at = now WHERE digest = ? AND replaced_at IS NULL` — so when two
  requests arrive with one token, the database awards the exchange to exactly one of them and the
  other is told it changed nothing. That is indistinguishable from somebody presenting a token that
  had already been spent, and is treated as exactly that: the session is revoked, because two
  parties hold a one-use credential and there is no way to tell which of them is its owner.
- **The revocation is committed even though the request is refused.** This is why renewal returns
  its refusal rather than throwing it: a use case runs in one transaction that rolls back on an
  unchecked exception (ADR-023), so throwing would undo the one response to a token having been
  stolen.
- **Every access token names its session** in a `sid` claim, so ending a session ends the tokens
  issued for it. Whether that bites on the next request depends on the per-request session check,
  which is a separate decision and lands with the bearer authentication.
- **The SPA serialises renewals across tabs with the Web Locks API.** Two tabs refreshing at once
  with one cookie is exactly the pattern reuse detection is built to punish, and it would be the
  application's own fault.
- **Both lifetimes are configuration** — an absolute one, fixed when the session starts and never
  extended, and an idle one that a renewal resets. The absolute limit is what stops a stolen refresh
  token being used to keep a session alive for ever.

## Consequences

**Easier.** A cross-site scripting flaw costs an attacker at most the remaining life of one access
token, rather than a renewable session. A stolen refresh token is usable at most once before it
announces itself, and the announcement ends the session rather than merely failing one request.
Signing out actually ends something, because there is a row to revoke.

**Harder, and honestly so.** The access token is gone on a page reload, so the application always
starts by renewing — a request before it can show anything. Rotation means the client and server
must agree on which token is current, so a renewal that succeeds on the server and is lost in
transit signs the user out; that is deliberate, and the price of treating a duplicate as theft.

`refresh_token` rows are kept rather than deleted when replaced, because a deleted row cannot say
"this was already spent". The table therefore grows without limit, and nothing purges it — **Q23**.

A wrongly-configured deployment that serves the SPA from a different origin than the API would break
both defences at once: `SameSite=Strict` would stop the cookie travelling, and the missing CORS
configuration would stop the header. That is a deployment constraint nothing in the code enforces.

Refusing and locking are both denial-of-service levers, as they were before, and there is still no
IP rate limiting.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| Refresh token in `localStorage` | Any script on the page can read it, which is precisely the attack the split is for. There is no threshold at which this becomes acceptable for a long-lived credential. |
| Non-rotating refresh tokens | Simpler, and gives up the only signal that a token has been copied: without rotation, a stolen token and its owner are indistinguishable for the token's whole life. Revisit never; rotation is what makes reuse detectable at all. |
| A JWT as the refresh token | Would avoid a lookup, except that revoking one needs a lookup anyway — so it buys nothing here and costs the ability to store only a digest. |
| A grace period accepting a just-replaced token | Would forgive a client that lost a response in transit, at the cost of blunting reuse detection into "reuse, but not too soon". **Revisit if production logs show reuse being detected on legitimate clients** rather than on attacks. |
| Server-side sessions only, with a `JSESSIONID` | Simpler for a same-origin SPA, and the owner chose bearer tokens (D1) so that a non-browser client and a second verifier remain possible. Revisit together with ADR-011's own trigger, not on its own. |
| CSRF tokens instead of the custom header | The standard answer, and a heavier one: it needs a token minted, delivered and checked. `SameSite=Strict` plus a header no cross-site request can send covers the same ground here because no CORS is configured. **Revisit if CORS is ever configured for any origin**, which removes the reason the header works. |
