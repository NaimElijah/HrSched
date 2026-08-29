# ADR-012: The acting identity carries the employee record it is

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

Requirement F6 says an employee declares their own unavailability and preferences, and
`Role.EMPLOYEE` is defined in those terms. The first implementation decided "is this actor the
subject?" by comparing the acting `UserId`'s UUID with the subject `EmployeeId`'s UUID.

Those are deliberately different identities — `UserId` says so in its own documentation, because
not every user is rostered and not every rostered person logs in. No mapping between them existed
anywhere in the codebase, so the comparison was **always false**. Every employee acting on their
own record fell through to the manager check and was refused. F6 was unimplementable, and the only
test covering it passed because it constructed `new UserId(employeeId.value())` — a value no
production path can produce.

That is worth recording rather than quietly patching, because the missing piece is a modelling
gap and not a typo: nothing in the system said which person a user *is*.

## Decision

**`TenantActor` carries `Optional<EmployeeId>`: the personnel record this user is, empty for a
user who is not rostered.** Self-service is decided by `isThemself(EmployeeId)` against that link,
never by comparing two identifier types that are documented as unrelated.

**The link is product data, not authentication.** An identity provider answers *who is signed in*;
which employee record that person corresponds to is HrSched's own fact, needed under any provider
chosen in ADR-011. It is therefore modelled now rather than deferred with the placeholder.

**It goes in the shared kernel because `Actor` is already there** and the alternative is worse:
`workforce` would have to publish a user-to-employee lookup that `access` and `scheduling` also
need, which is a shared kernel with extra indirection.

**The placeholder web adapter supplies it from an `X-Employee` header**, alongside the equally
insecure organisation and role headers, and is deleted with them.

## Consequences

**Easier**

- F6 works, and its test proves it: the actor's `UserId` is unrelated to the employee id, so the
  test can only pass through the link.
- The privilege is explicit. "This actor may act on this record because they are that person" is
  one named method, not an identifier coincidence.
- Authorisation stops depending on how identifiers happen to be generated. Nothing breaks if user
  and employee identities are later issued by different systems.

**Harder**

- **The kernel grew, which ADR-008 named as the failure mode to watch.** The justification is that
  this is an identity relationship between two kernel types and carries no behaviour beyond the
  question `isThemself`. It is one field, and the bar for the next one is not lower because this
  one was allowed.
- **Nothing yet keeps the link truthful.** With header-based identity a caller states which
  employee they are, so it is a claim, not a fact. It becomes a real guarantee only when the
  identity provider establishes it (ADR-011); until then the whole actor is insecure by design.
- **A record with an `Optional` component.** Accepted because absence is a genuine domain state —
  a user who is not rostered — rather than a not-yet-loaded field, and a convenience constructor
  keeps the common case terse.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Make `UserId` and `EmployeeId` the same identifier** | Removes the problem entirely and is what the broken comparison assumed. Rejected because it is false in the domain: a manager who is never rostered has no employee record, and an employee who never signs in has no user. Forcing them to coincide would make one of those unrepresentable. |
| **Look the employee up from the user inside `EmployeeService`** | Keeps the kernel untouched. Rejected because every context that authorises a self-service action would need that lookup, so it becomes a shared dependency anyway — with a repository call on the authorisation path instead of a value already in hand. |
| **Drop self-service until authentication lands** | Honest, and smaller. Rejected because it removes a requirement the owner confirmed rather than deferring an unknown, and the user-to-employee link is needed under every candidate identity provider, so nothing about it is actually blocked on Q12. |
| **Give `Actor` the link rather than `TenantActor`** | Fewer types to know about. Rejected because an `Operator` belongs to no organisation and is emphatically not an employee; an always-empty field on it would invite exactly the confusion this ADR exists to remove. |
