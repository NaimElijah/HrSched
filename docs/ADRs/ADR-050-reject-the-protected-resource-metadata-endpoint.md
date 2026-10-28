# ADR-050: Reject the OAuth 2.0 protected-resource metadata endpoint

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

Spring Security 7.1's resource server, which [ADR-011](ADR-011-issue-our-own-access-tokens.md)
adopts to verify our own tokens, always adds `OAuth2ProtectedResourceMetadataFilter`. The full-system
security scan (#250) found what that does here:

- **It serves a route no rule names.** The filter answered
  `GET /.well-known/oauth-protected-resource/**` ahead of every authorisation rule, which goes
  against [ADR-032](ADR-032-close-the-security-chain-by-default.md): "every route is named by a
  rule".
- **What it served was false.** The metadata claimed certificate-bound access tokens, which this
  service does not issue, and it built `resource` from the request's `Host` header, so the caller
  chose what the document said.
- **Every 401 pointed at it.** `BearerTokenAuthenticationEntryPoint` wrote a `resource_metadata`
  parameter into each `WWW-Authenticate` challenge.
- **No client of this API reads it.**
- **Spring Security 7.1 (checked on 7.1.2) has no switch to turn it off.** The filter is final and its matcher is
  fixed, and the entry point always writes the parameter. Its resolver can change the address but not
  leave it out.

## Decision

**We reject the protected-resource metadata endpoint (RFC 9728), and remove it from the chain.**

- **`SecurityConfiguration` rebuilds the built chain without the filter.** It does so through the
  public `DefaultSecurityFilterChain` constructor, so the path is named by no rule and falls to
  `denyAll` like any other.
- **`ProblemAuthenticationEntryPoint` writes the RFC 6750 `WWW-Authenticate` challenge itself, with
  no `resource_metadata`.** Every refused token carries the same description, whatever refused it,
  as it did since `1967707`; a request with no token carries the bare scheme.

## Consequences

**Easier**

- **ADR-032 holds again.** The path is refused like any unnamed route, and
  `ProtectedResourceMetadataHttpTest` asserts it and that no challenge mentions `resource_metadata`.
- **A challenge no longer points a client at a document this service refuses to serve.**
- **No false claim about our tokens is published**, and no response varies with a caller-chosen
  `Host`.

**Harder**

- **The chain is post-processed after Spring builds it.** A Spring Security upgrade that changes the
  built chain's shape or the filter's type can break the rebuild, or make it remove nothing without
  failing. The test is what catches that.
- **The challenge is hand-written.** We now own its formatting (`error`, `error_description`,
  `error_uri`) instead of inheriting Spring's, and a change in Spring's challenge will not reach us.
- **A client that wants to discover the API through RFC 9728 cannot.**
- **An `invalid_request` is answered 401, where RFC 6750 §3.1 says 400.** The entry point answers
  every refusal with a 401, as it did before this branch. Nothing in this configuration produces an
  `invalid_request`, because query and form tokens are off, so the status is wrong only for a
  refusal that cannot arise today.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Correct the metadata through `ProtectedResourceMetadataConfigurer`** | Could fix what the document says. Rejected because it cannot make the endpoint go away, nor remove the certificate-bound claim, and a document we do not need would stay a route no rule names. |
| **Wire `BearerTokenAuthenticationFilter` by hand, without `oauth2ResourceServer()`** | Would never add the filter. Rejected: far more code, and hand-rolled security wiring is what ADR-011 chose a library to avoid. |
| **Leave it** | Rejected for the reasons above: a false document, and a route outside ADR-032. |

**Revisit when** a client needs to discover the API through RFC 9728, or when Spring Security offers
a supported switch for the filter and the challenge parameter, at which point the rebuild should be
replaced by it.
