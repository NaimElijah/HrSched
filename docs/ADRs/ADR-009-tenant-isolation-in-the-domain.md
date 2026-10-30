# ADR-009: Carry tenancy in the domain and enforce it at the application boundary

- **Status:** Accepted
  - **Amended 2026-10-30:** The consequences about an in-memory store and "authentication, which
    does not exist yet" have moved: every aggregate is stored in PostgreSQL
    ([ADR-004](ADR-004-postgresql-behind-the-driven-ports.md)), and callers authenticate
    ([ADR-011](ADR-011-issue-our-own-access-tokens.md)). An `Operator` no longer "may reach any"
    organisation: [ADR-030](ADR-030-confine-the-operator-to-the-register-of-organisations.md)
    confines it to the register of organisations, narrowing that sentence and not the decision.
  - **Amended 2026-10-30:** Not every repository lookup takes an `OrganisationId`: the reads that
    run before an organisation is known, and the register itself, are listed in
    [ADR-055](ADR-055-read-an-account-or-session-without-an-organisation-where-none-is-known.md).
- **Date:** 2026-08-30

## Context

Q3 is answered: HrSched is **multi-tenant**. Independent organisations share one deployment and
must never see one another's data. A failure here is not a bug with a workaround — it is one
customer reading another customer's staffing, and it is the kind of failure that ends a product.

The usual way this goes wrong is not a dramatic mistake. It is a repository method that forgot a
`WHERE organisation_id = ?`, written by someone in a hurry, reviewed by someone who assumed it
was there.

## Decision

**Every aggregate carries its `OrganisationId`.** `Employee`, `Shift`, `Roster`, `Qualification`
and `ConstraintSet` all hold one, and it is required at construction.

**Every repository lookup takes an `OrganisationId` as a parameter.** There is deliberately no
`findById(EmployeeId)`. Making the tenant scope part of the signature means a cross-tenant read
cannot be written by *forgetting* something — only by explicitly passing the wrong organisation,
which is a visible act in a review.

**Every use case takes an `Actor` and checks it first.** `Actor` is a sealed interface:
`TenantActor` may reach exactly its own organisation, `Operator` may reach any. Sealing it closes
the set of things able to cross a tenant boundary and puts them in one file. The check happens in
the application service, not the adapter, because there is more than one adapter and a guarantee
that depends on every caller remembering is not a guarantee.

**A cross-tenant attempt is reported as absence, not refusal.** Acting on an identity belonging
to another organisation yields "no such employee", and the HTTP layer returns **404, not 403**. A
403 confirms the resource exists somewhere, which is a small leak of exactly the kind this
decision exists to prevent.

**Isolation has its own tests**, asserting that an actor of one organisation cannot read, write,
or learn of the existence of another's data — rather than trusting that the scoping is present.

## Consequences

**Easier**

- The compiler participates. A repository method without an organisation parameter does not
  exist to call.
- The privileged path is one named type. Every cross-tenant read in the product traces back to
  `Operator`, so there is exactly one thing to audit.
- Moving to a real database preserves the shape: the port signatures already say what the
  `WHERE` clause must be.

**Harder**

- **Every signature is longer.** Actor and organisation thread through every use case, every
  command and every repository call. It is verbose, and that verbosity is the point.
- **Nothing stops an `Operator` doing anything.** The type is deliberately unrestricted, so the
  protection is entirely in how few of them exist and who can obtain one — which depends on
  authentication, which does not exist yet.
- **Enforcement is not database-level.** A row-level security policy would be a second, independent
  guarantee. With an in-memory store there is nothing to configure; when ADR-004 lands, the
  question of defence in depth should be asked again.
- The in-memory adapters filter in Java rather than in a query, so they demonstrate the intent
  without proving the eventual implementation.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **A database per tenant, or a schema per tenant** | Strongest isolation, and it makes the domain simpler because nothing needs an organisation at all. Rejected as an operational commitment made far too early — provisioning, migrating and monitoring N databases before there is one customer. **Revisit under** a customer with a regulatory requirement for physical separation, or when tenant count is small and stable. |
| **Ambient tenant context** (a thread-local or request-scoped bean) | Much less verbose; the organisation would not appear in any signature. Rejected because it makes the most safety-critical property in the system invisible — it becomes impossible to tell by reading a method whether it is scoped, and a background thread or an async call silently loses it. |
| **Enforce only in the adapter layer** | Fewer checks. Rejected because the guarantee would hold only for callers that remembered, and there is already more than one adapter. |
| **Filter in the repository implementation rather than the signature** | Keeps ports terse. Rejected because it is precisely the shape that fails: the parameter is what makes the omission impossible rather than merely unlikely. |
