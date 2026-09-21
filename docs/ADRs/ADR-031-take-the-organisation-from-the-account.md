# ADR-031: Take the organisation from the account, not from the request

- **Status:** Accepted
- **Date:** 2026-09-21
- **Supersedes:** [ADR-020](ADR-020-refuse-a-request-naming-an-unknown-organisation.md)

## Context

Until now every request inside a tenant carried `X-Organisation`, and the system worked out which
customer's data it was touching by reading it. [ADR-020](ADR-020-refuse-a-request-naming-an-unknown-organisation.md)
closed the worst consequence of that — a caller inventing a UUID and getting a private corner of a
system nobody had onboarded — by checking the header against the register of tenants before any
handler ran.

[ADR-011](ADR-011-issue-our-own-access-tokens.md) and the units after it replaced the identity
headers with an access token this service signs, and every controller now resolves the acting
account from stored state on each request. An account belongs to exactly one organisation
(ADR-012), and that organisation exists because an account cannot be created outside one
(ADR-029).

So the question ADR-020 answered no longer arises the same way. There is no longer a *claimed*
organisation to validate.

## Decision

**A request acts in the organisation its account belongs to, and in no other. The organisation is
read from the authenticated actor and never from a header, a path or a body.**

`Actor.requireTenantMembership()` returns the caller as a member of their own organisation, or
refuses. Each web adapter has one `actingIn(actor)` helper that calls it, and that helper is the
only place an organisation enters a request.

**`KnownOrganisationFilter` is deleted, and ADR-020 is superseded.** The filter checked a claim
that callers can no longer make. Keeping it would mean keeping a filter that can never fire — and
a check that cannot fail is worse than no check, because it reads like protection.

**This is strictly stronger than what it replaces.** ADR-020 caught an organisation that did not
exist; it could not catch a caller naming an organisation that did, because `ActorFromHeaders`
would have built an actor for it and `requireAccessTo` compared that actor's claimed organisation
with the claimed target — two halves of the same lie. Now the organisation comes from a row the
caller cannot write, so there is nothing in the request to disagree with.

## Consequences

**Easier.** There is one place per adapter that knows which organisation a request is for, and it
takes no input. A route cannot forget to scope itself, because there is no unscoped form of the
call. The platform operator is refused inside every tenant structurally rather than by a check:
it belongs to no organisation, so `requireTenantMembership` has nothing to return (ADR-030).

**Harder.** Somebody who works for two customers needs two accounts and two sessions; there is no
way to switch organisation within one. That was already the decision `TenantMembership` encoded,
and this makes it binding rather than conventional.

**Also harder.** Support cannot reproduce a customer's request by pointing a tool at their
organisation. Whether anything should be able to is Q28, which asks how a tenant recovers when its
only Admin is locked out.

**The register is still needed**, and is still `KnownOrganisationsUseCase` — onboarding writes to
it and the operator reads it (F13). What went away is one consumer of it, not the register.

## Alternatives considered

- **Keep the filter as defence in depth.** Rejected: it has nothing left to check. A filter reading
  a header no client sends and no controller consults is not defence, it is a comment that looks
  like code, and the next person to read it would reasonably believe the header was still
  load-bearing.
- **Let an operator name an organisation and act inside it.** Rejected by ADR-030, and this
  decision is part of how that is enforced rather than merely stated.
- **Allow one account to hold several memberships and name which one a request uses.** Rejected for
  now: it puts the organisation back in the request, and every tenant-scoped query would have to
  ask which membership was meant. Revisit if somebody genuinely needs one credential across
  customers — that is the trigger, and it would need its own ADR because it changes what
  `TenantMembership` means.
