# ADR-020: Refuse a request naming an organisation nobody onboarded, at the boundary

- **Status:** Superseded by [ADR-031](ADR-031-take-the-organisation-from-the-account.md)
- **Date:** 2026-09-01

## Context

> **Tenant isolation** — the deployment is shared by independent organisations that must never see
> one another's data. Every aggregate belongs to exactly one organisation, and every query is scoped
> to it. **A cross-tenant read is a security incident, not a bug.**

That is what `requirements.md` claims, and every repository read does filter by `OrganisationId`.
What none of it established was that the organisation being filtered by is *real*.

`X-Organisation` was believed on sight. A caller could run `uuidgen`, put the result in the header,
and get a working private corner of the system: employees, qualifications, shifts, constraint sets
and rosters, all stored, all readable back, all belonging to a tenant that had never been onboarded
and that no operator could see. The README's quick start did exactly this and worked. Isolation was
real between organisations that existed; it said nothing about organisations that did not.

ADR-019 made the check possible by giving the platform a register of tenants. This decides what to
do with it.

## Decision

**We refuse any `/api/**` request whose `X-Organisation` names an organisation nobody onboarded,
with a 404, before it reaches a controller.** `KnownOrganisationFilter` sits in
`configuration.tenancy` and consumes `access`'s published `KnownOrganisationsUseCase`.

**404, not 403 or 400.** ADR-009 already fixed this shape: a caller is told there is nothing here
for them, never that something exists which is not theirs. The body is the one an unknown record
produces — same status, same title, same detail — and echoes nothing the caller sent.

**Onboarding is exempt.** `POST /api/organisations` is the bootstrap: requiring an organisation to
create the first organisation is a platform that can never be started.

**A missing or malformed header passes through.** Whether an operation needs the header is the
controller's business, and it answers 400 naming it; `ActorFromHeaders` already turns an unparseable
identifier into a 400. A 404 would report a plainly broken request as a missing one.

**It is a servlet filter in `configuration`, not a change to the three `ActorFromHeaders` copies.**
One rule about tenancy rather than three copies of adapter plumbing; `configuration` is already the
only package permitted to see more than one context; and it outlives ADR-011, because whether a
tenant exists is a different question from who the caller is.

**It is ordered explicitly**, immediately after `TraceIdResponseFilter`, so a refusal still carries
`X-Trace-Id` and can be quoted by whoever received it.

## Consequences

**Easier**

- Data can no longer be created under a tenant that does not exist. This is the property the change
  is for, and it is asserted directly: disabling the check turns
  `GivenAnOrganisationNobodyOnboarded_WhenCreatingDataUnderIt_ThenItIsRefused` from green into
  `expected:<404> but was:<201>`.
- Every organisation holding data is now an organisation an operator can enumerate. Before this,
  invented tenants were invisible to the platform that was storing them.
- Refusals are counted as `hrsched.access.denied{kind="unknown_organisation"}`, so an enumeration
  attempt looks like something rather than like nothing.

**Harder**

- **This is an oracle for whether an identifier names a real tenant.** A known organisation answers
  differently from an unknown one, and no shaping of the body changes that. What makes it acceptable
  is arithmetic rather than design: an organisation identifier is a random version-4 UUID, so
  guessing one is 2^122 attempts. It is recorded here rather than defended, because the alternative
  on offer was believing every identifier equally.
- **It is not authentication and does not make the header placeholder safe.** It establishes that a
  claimed tenant is one of ours, not that the caller belongs to it. Anyone who learns a real
  organisation identifier can still claim any role inside it, including `ADMIN`. ADR-011 is still
  owed, and this raises what it is worth rather than substituting for it.
- **A restart is now a total outage rather than a data loss.** The register is in memory
  (ADR-019), so after a restart every organisation is unknown and every tenant's every request
  answers 404 — where previously the system would have come back up empty but working. This is the
  sharpest edge ADR-004 has to cut, and this decision created it.
- **Every HTTP test had to start onboarding a real organisation.** `anOrganisation()` in three test
  classes changed from returning a random UUID to performing a real `POST /api/organisations`. That
  is the change working as intended — a random UUID now fails every test that uses it, for the right
  reason — but it is churn across roughly 70 call sites, and the same applies to the README quick
  start.
- Two filters now carry explicit `@Order` values, and one of them is load-bearing in a non-obvious
  way: ordering `TraceIdResponseFilter` before Boot's observation filter (registered at
  `HIGHEST_PRECEDENCE + 1`) silently removes `X-Trace-Id` from every response, because no span exists
  yet. That was introduced and caught by `TraceIdHttpTest` while making this change.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Leave it: keep believing the header** | Zero work and nothing breaks. Rejected because the requirement it violates is the one the documents call a security incident, and "every query is scoped to an organisation" is worth nothing when the organisation is whatever the caller typed. **Revisit: never.** |
| **Check it in each `ActorFromHeaders`** | Puts the check beside the other header parsing. Rejected: three copies of one rule, three places to forget it, and it welds a permanent tenancy rule onto the class ADR-011 exists to delete. **Revisit if** the filter ever needs per-context behaviour, which would mean it is no longer one rule. |
| **Check it in each application service** | Inside the hexagon, testable without HTTP. Rejected as the wrong altitude and a worse cost: it is a boundary validation, and it would mean an `access` port injected into every service in every context — exactly the coupling ADR-008 keeps out. |
| **Answer 403** | Says plainly that the caller may not act for that organisation. Rejected by ADR-009's own reasoning: a 403 confirms the resource exists. |
| **Answer 400** | Treats an unknown tenant as a malformed claim, which is arguably what it is. Rejected because it tells an attacker the organisation does not exist in the one word the 404 withholds, and because the header is well formed — it is the claim that is false, not the syntax. |
| **Auto-create the organisation on first use** | Makes the quick start keep working and nothing 404s. Rejected outright: it is the current behaviour with a friendlier name, and it would mean any caller can still mint a tenant, which is the whole problem. |
| **Exempt the whole `/api/organisations` path rather than only `POST`** | Simpler predicate. Rejected because only the bootstrap needs the exemption; a tenant reading `/api/organisations/{id}` should be subject to the same check as anything else. |
