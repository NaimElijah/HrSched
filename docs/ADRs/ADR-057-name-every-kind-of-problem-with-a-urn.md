# ADR-057: Name every kind of problem with its own URN

- **Status:** Accepted
- **Date:** 2026-10-31

## Context

Every refusal this API writes is an RFC 9457 problem detail
([ADR-024](ADR-024-answer-unbindable-requests-with-a-problem-detail.md)), built in nine places: the
four contexts' exception handlers, `AuthController`, the security chain's entry point and
access-denied handler, `JsonCharsetFilter` and `RequestBindingExceptionHandler`. Issue #275 adds a
tenth, `RequestRoutingExceptionHandler`, for the refusals Spring makes on its own. Each set its own
title, such as `Already assigned` or `Roster breaks the rules`, and none set `type`, which therefore
stayed `about:blank`.

RFC 9457 section 4.2.1 says that with `about:blank` the title SHOULD be the status code's phrase,
`Conflict` or `Unprocessable Content`. So the API broke a SHOULD on every refusal, and a client had
nothing but the title to tell two 409s apart: a swap already decided from a roster whose status
forbids the change. A title is prose, written to be read; changing its wording would silently break
any client that compared it.

The kinds overlap across producers. `Not found`, `Not permitted`, `Invalid request`, `Malformed
request` and `Request too large` are each answered by several contexts, and until issue #256 the
chain titled its 403 `Forbidden` while every context titled the same refusal `Not permitted`.

A context may reach another context's published ports and nothing else
([ADR-008](ADR-008-context-integration.md)), and an adapter may not reach `configuration`, so there
is no class every producer could share.

## Decision

**We give every kind of problem a stable type URI, `urn:hrsched:problem:<kind>`, where the kind is
its title in kebab case:** `urn:hrsched:problem:already-assigned` for `Already assigned`. One kind
has one title, one status and one type, and keeps that type wherever it is answered, so `not-found`
is the same URI in all four contexts and in front of them. `docs/api.md` lists every type with its
status and title.

**Each producer's package holds the URIs it answers with as named constants, in a class of its own
named `ProblemTypes`:** one in each context's `adapter.in.web`, and one in `configuration.web` that
the security chain uses too. Never in the domain: what kind of problem a refusal is on the wire is
the transport's business. The type is set where each problem is built, beside its title.

**`ProblemTypesTest` keeps the copies equal.** It finds every class named `ProblemTypes` in the
application and holds each constant to its own name: `NOT_FOUND` must be
`urn:hrsched:problem:not-found`. Two constants of one name therefore cannot hold two URIs, and a
renamed constant fails until its URI is renamed with it, which makes changing a type a deliberate
act. It also reads every other class file in the application, and fails if one holds the prefix,
so a type cannot be written inline where a problem is built, out of every constant's reach. HTTP
tests assert `$.type` for at least one problem of every kind a request can reach, and the handler
tests assert it wherever a handler sets one. Four kinds are asserted only there, because no HTTP
test of the application reaches them: `generation-is-busy`, `published-concurrently`,
`revised-concurrently` and `roster-cannot-be-checked-against-the-rules`.

**A URN, not a URL.** The type names a kind and is never fetched; nothing is served at it.
`urn:hrsched:` is not a registered URN namespace, and RFC 8141 section 5 makes registration what
makes a namespace one, so these are URN-shaped names rather than valid URNs. RFC 9457 section 3.1.1
names the `tag` scheme (RFC 4151) for a type that is not meant to be resolved. Which authority the
types are minted under is the repository owner's decision, tracked in
[#342](https://github.com/NaimElijah/HrSched/issues/342); until it is made the prefix stays as it
is, and it is settled before any client other than the SPA reads `type`.

**The titles stay as they are.** With a type of their own, RFC 9457 lets each title be a short
summary of that type rather than the status phrase.

## Consequences

**Easier**

- A client can branch on the kind of refusal without comparing prose, and a title can be reworded
  without breaking one. The SPA keeps the type on the error it parses, and branches on the status
  for now, because nothing yet needs more.
- Every problem detail satisfies RFC 9457 section 4.2.1 as it stands.
- A new kind is visible in review: a constant, a row in `docs/api.md` and an HTTP assertion.

**Harder**

- A type is a contract. Renaming a kind is a breaking change for any client that compares it, which
  today is none: the only consumer is the SPA, which ships with the backend.
- The prefix is provisional. `urn:hrsched:` is registered nowhere, and moving to a `tag:` URI or to
  resolvable URLs changes every type's identity at once. That is free while the SPA is the only
  reader and ships with the backend, and is a breaking change for every client after, which is why
  [#342](https://github.com/NaimElijah/HrSched/issues/342) is settled before any other client reads
  `type`.
- A kind answered in several places is written down in each of them. The test keeps the copies
  equal, but nothing stops a producer from inventing a second kind where it meant an existing one;
  review and the table in `docs/api.md` are what catch that.
- Nothing ties a type to its title and status in code. The table and the HTTP tests, which assert
  both, are what hold them together.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Leave `type` at `about:blank` and set every title to the status phrase** | RFC-conformant with no new contract. Rejected: it throws away the only thing that tells two refusals of one status apart, `Already assigned` and `Roster status forbids this` both becoming `Conflict`, and the SPA would show a client nothing it can act on. **Revisit if** every status the API answers comes to mean one kind of refusal only, so that a type would say nothing the status does not. |
| **Dereferenceable URLs, `https://…/problems/<kind>`, each serving a page** | RFC 9457 recommends them, because a person can follow one. Rejected for now: HrSched has no stable public origin to publish under (no record decides one yet; ADR-035 is reserved for the topology, [#153](https://github.com/NaimElijah/HrSched/issues/153)), a URL nothing serves is worse than a URN that promises nothing, and the only consumer is the SPA, which ships with the backend. **Revisit when** a client other than our own SPA consumes the API, or the API's documentation is published at a stable origin, and in either case as part of [#342](https://github.com/NaimElijah/HrSched/issues/342). |
| **`tag:` URIs (RFC 4151) under an authority the owner holds** | The scheme RFC 9457 section 3.1.1 names for a type nobody resolves, and valid where an unregistered `urn:` is not. Not chosen here, rather than rejected: it needs an authority, a domain or an e-mail address the owner controls, with a date, and choosing one is the owner's decision. **Revisit when** [#342](https://github.com/NaimElijah/HrSched/issues/342) is decided, and in any case before any client other than the SPA reads `type`. |
| **One shared class of URIs for every producer** | One copy instead of five. Rejected: there is no package every producer may reach. The kernel holds domain types only, contexts may not reach one another's adapters (ADR-008), and adapters may not reach `configuration`. **Revisit if** a record superseding ADR-008 gives the web adapters a package they may all reach. |
| **The URIs in the domain exceptions** | Each exception would carry its own kind. Rejected: the domain may not know about the transport, and which status and kind a refusal earns is the adapter's decision, as every status already is. **Revisit if** a second driving adapter, such as a message consumer, has to report the same kinds, so that a kind stops being HTTP's alone. |
| **A type per status rather than per kind** | Fewer URIs. Rejected: it carries no more than the status code already does. **Revisit if** the kinds come to outnumber what any client tells apart, and every client is found to branch on the status alone. |
