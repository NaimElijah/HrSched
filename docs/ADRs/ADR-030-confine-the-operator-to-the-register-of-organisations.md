# ADR-030: Confine the platform operator to the register of organisations

- **Status:** Accepted
  - **Erratum 2026-10-30:** "Q27" for the tenant whose only Admin is locked out should read Q28.
    Q27 is the security defaults.
  - **Amended 2026-10-30:** `inboxOf` no longer keeps an `instanceof`. Its operator branch could
    not be reached, because `requireAccessTo` refuses an operator first, so it now takes the
    `TenantActor` that guard hands back ([#254](https://github.com/NaimElijah/HrSched/issues/254)).
    The decision stands.
- **Date:** 2026-09-16

## Context

Every tenant use case guarded itself with the same two lines:

```java
actor.requireAccessTo(organisation);
if (actor instanceof TenantActor tenant) {
  tenant.requireRoleIn(permitted);
}
```

Read it twice. `requireAccessTo` passed for an `Operator`, because `Operator.mayAccess` returned
true for every organisation. The `instanceof` then failed to match, so the role check was
**skipped** rather than failed — and the method carried on. The idiom looked like two guards and
was, for the one identity that belongs to no tenant, none.

The consequence was not subtle. The platform operator could read any organisation's rosters — which
name every colleague and when they work — list its staff, revise its working-time constraint set,
publish rosters in its name, approve swaps, and read inboxes. Nine places used the idiom, and any
new use case that copied it would have inherited the hole.

The owner's decision (D5, 2026-09-16) is that the operator onboards, lists and reads organisations,
and does nothing else.

This is also the moment to fix it. [ADR-011](ADR-011-issue-our-own-access-tokens.md) makes the
acting identity something the system establishes rather than something a caller claims, so "who is
the operator" is finally a fact rather than a header — and confining them becomes meaningful rather
than decorative.

## Decision

**We close the hole in the kernel, so that no use case can reopen it.**

- **`Actor.requireAccessTo(OrganisationId)` now returns the `TenantActor` it proved.** The guard used
  to be a statement and is now an expression, so the next line has a member to ask a role of and
  there is nothing left to skip. Every guard became one line:
  `actor.requireAccessTo(organisation).requireRoleIn(MAY_…)`.
- **It is a `switch` over the sealed `Actor`,** not a boolean test, so a third kind of actor cannot be
  added without deciding here what it may reach.
- **`Operator.mayAccess` returns false**, and an operator reaching inside an organisation raises the
  new kernel `NotATenantMemberException`.
- **That refusal is a 403, where a cross-tenant attempt stays a 404.** The 404 exists so a tenant
  cannot learn that another organisation exists; an operator already knows, because listing them is
  the job, so there is nothing left to conceal.
- **`OrganisationService.findOrganisation` is the one exemption,** and says so in a comment: an
  operator may read any organisation in the register (F13). It is the register, not anything inside
  a tenant.
- **`SwapService.isVisibleTo` now takes a `TenantActor`** rather than an `Actor`, so the
  "not a tenant, therefore see everything" branch it used to have is unrepresentable.

`personalRecordOf` and `inboxOf` keep an `instanceof`, and are the only two that do. They already
refuse an operator — there is no personnel record and no inbox to answer for — and they answer a
different question from the tenancy guard.

## Consequences

**Easier.** A new use case that follows the house idiom is safe by default: there is no way to write
the guard that skips a check, because the guard now hands over the thing the next line needs. The
most privileged identity in the system reaches exactly one table, and the blast radius of a leaked
operator credential is the list of customer names rather than every customer's staffing.

**Harder, and honestly so.** There is now no way for an operator to look at a tenant's data, which
means no way to reproduce a customer's bug by looking at it. If support access is ever needed it has
to be built: its own feature, its own audit trail, and its own decision about consent. That is the
right shape for it, and it is more work than leaving the bypass in.

The operator can also no longer "act inside" a tenant from the UI. That capability only ever existed
because the frontend could write any identity header, and it goes when the header identity does.

A tenant whose only Admin is locked out cannot be helped by the operator either — **Q27**, and not
solved here.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| Keep the bypass | It is the defect. There is no threshold at which "the operator can silently read every customer's staffing" becomes acceptable. |
| Read-only support access across tenants | Genuinely useful, and genuinely a feature rather than a side effect of a guard that did not fire. **Revisit when a support process is defined** — who may invoke it, whether the customer is told, and what the audit trail records. |
| Put `TenantActor` in every tenant driving-port signature | Stronger still: an operator could not even be passed to a tenant use case, and the refusal would be a compile error rather than an exception. It touches every driving port, every controller and every test. **Revisit if a second non-tenant actor kind ever appears**, at which point the sealed `switch` here stops being enough. |
| Check for the operator in each service | What the old idiom effectively was, minus the bug. Nine places to remember, and a tenth the next time somebody adds a use case. |

## Relation to earlier decisions

This **narrows** [ADR-009](ADR-009-tenant-isolation-in-the-domain.md) and does **not** supersede it.
ADR-009's decision — that tenancy lives in the domain and is enforced at the application boundary —
stands unchanged, and is what made this fixable in one place. What narrows is one sentence of its
description: an `Operator` no longer "may reach every organisation". It administers the register of
them.
