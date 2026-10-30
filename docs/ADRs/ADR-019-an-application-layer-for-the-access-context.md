# ADR-019: Give the access context an application layer, and make the organisation a real record

- **Status:** Accepted
  - **Amended 2026-10-30:** The register of organisations is stored in PostgreSQL
    ([ADR-004](ADR-004-postgresql-behind-the-driven-ports.md)), so a restart no longer empties it.
    The total outage the text describes went with
    [ADR-020](ADR-020-refuse-a-request-naming-an-unknown-organisation.md), which
    [ADR-031](ADR-031-take-the-organisation-from-the-account.md) superseded. "There is still no
    authentication" no longer holds ([ADR-011](ADR-011-issue-our-own-access-tokens.md)).
  - **Amended 2026-10-30:** `OrganisationRepository` is no longer the one repository here whose
    reads are not scoped to a tenant: the credential reads listed in
    [ADR-055](ADR-055-read-an-account-or-session-without-an-organisation-where-none-is-known.md)
    are not either.
- **Date:** 2026-09-01

## Context

> F13 — Operator — Administer organisations across the platform — *partially: an Operator may act
> across tenants; there is nothing to administer yet.*

`access` was the only bounded context with no application layer. It held two classes,
`Organisation` and `InvalidOrganisationNameException`, and nothing anywhere imported either of
them: `grep -r "import io.github.naimelijah.hrsched.access" backend/src` returned nothing at all.
The entity was modelled, tested, and entirely orphaned.

The consequence was not cosmetic. `OrganisationId` is the tenant boundary of the whole system —
every aggregate carries one, every repository lookup takes one (ADR-009) — and yet no organisation
was ever created or stored. A tenant was whatever UUID a caller put in `X-Organisation`. The
README's own quick start made this plain by conjuring one with `ORG=$(uuidgen)`, and it worked,
because there was nothing for it to be checked against.

Q7 answered who administers organisations — *a platform Operator, the only identity crossing
tenants* — on 2026-08-30. `Operator` was built for it and documented as "the most dangerous
identity in the system". It had no use case of its own.

## Decision

**We give `access` the same hexagon every other context has**: driving ports, driven ports, a
service with no framework annotation, an in-memory repository, an audit trail, and a controller.

**Onboarding and listing organisations belong to the `Operator` alone.** The guard is the inverse
of every other one in this codebase. Elsewhere the question is which `Role` a tenant user holds;
here it is whether the actor belongs to a tenant at all, and a tenant Admin fails it however
privileged they are inside their own walls. That needs its own exception — `requireRoleIn` takes a
`Role`, and an `Operator` has none — so `NotThePlatformOperatorException` is added, and it maps to
403.

**That exception lives in `access.domain.model`, not the shared kernel.** Only this context asks
the question, and the kernel stays cheap only while it stays small (ADR-008).

**Reading one organisation is the exception, and uses the ordinary tenant guard.** An operator may
read any; a tenant may read its own, which `Actor.requireAccessTo` already expresses exactly.

**`OrganisationRepository` is the one repository here whose reads are not scoped to a tenant**,
because it *is* the register of tenants. What keeps that safe is the use case above it, where
listing is refused to anyone who belongs to one.

**`KnownOrganisationsUseCase` is published, and deliberately takes no `Actor`.** It answers one
question — has this organisation been onboarded — and it is consumed by the boundary check in
ADR-020, which runs before an actor built from an unverified header claim means anything. Requiring
an actor would be circular.

## Consequences

**Easier**

- F13 is a capability rather than a note. An operator can onboard a tenant, list every tenant, and
  read one.
- ADR-020 becomes possible at all: there is now something an `X-Organisation` claim can be checked
  against.
- `Operator` stops being a type that only unit tests and a header string can reach. The class the
  kernel calls "the most dangerous identity in the system" now has exactly one place that grants it
  anything, and that place has tests.
- The cross-tenant 404 is reachable over HTTP for the first time. `architecture.md` records that
  everywhere else the actor's organisation and the target organisation come from the same header, so
  `requireAccessTo` compares an organisation with itself; on `GET /api/organisations/{id}` the
  identity comes from the header and the target from the path, so the two can genuinely differ.

**Harder**

- **The register of tenants is in memory, so a restart empties it.** Combined with ADR-020 that is
  not a degraded mode, it is a total outage: every organisation becomes unknown and every tenant's
  every request starts answering 404. This is the sharpest edge ADR-004 now has to cut, and it did
  not exist before this change.
- **There is still no authentication.** `X-Role: OPERATOR` is a header anybody can send, so
  "operator-only" means "only for callers who claim to be the operator". This ADR makes the operator
  reachable and useful, which raises what ADR-011 is worth, and does not substitute for it.
- A third `AuditTrail` / `AuditedChange` pair, and a third copy of `ActorFromHeaders`. Both are the
  deliberate duplication ADR-008 chose, and both are now visible three times rather than twice.
- `UseCaseConfiguration` now writes two of the three audit ports out fully qualified.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Leave `access` domain-only, and keep organisations implicit** | Nothing to build, and the system worked. Rejected because it did not work — it only appeared to. A tenant boundary nobody can enumerate, create, or verify is a boundary asserted by the caller, which is the opposite of what ADR-009 claims the system does. **Revisit: never.** This is the change that makes ADR-009's guarantee checkable. |
| **Put organisation management in a new `platform` context** | Cleanly separates "administering the platform" from "being a tenant". Rejected because `access` already exists, already owns `Organisation`, and is named for exactly this. A new top-level package needs a new bounded context and its own ADR (CLAUDE.md), and there is no second concept here to justify one. **Revisit if** user provisioning, billing, or plan limits arrive — three concepts would outgrow `access`. |
| **Put `NotThePlatformOperatorException` in the shared kernel beside `NotPermittedException`** | Symmetrical, and the two refusals read as a pair. Rejected because the kernel is deliberately tiny and depends on no context (ADR-008), and exactly one context throws this. **Revisit if** a second context needs an operator-only operation. |
| **Give `KnownOrganisationsUseCase` an `Actor` like every other port** | Consistent. Rejected as circular: its caller is the check that decides whether a claimed tenant is real, and it runs before any actor built from that claim can be trusted. Consistency here would be a ritual, not a guarantee. |
| **Model onboarding as self-service sign-up** | The obvious SaaS shape. Rejected because Q7 answered otherwise — a platform Operator administers organisations — and inventing a sign-up flow would answer a question the repository owner has already answered differently. |
