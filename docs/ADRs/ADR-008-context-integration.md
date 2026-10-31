# ADR-008: Contexts integrate through published ports and a minimal shared kernel

- **Status:** Accepted
  - **Amended 2026-10-30:** the build now holds "consuming a published port happens in an
    adapter": `contexts_touch_only_published_ports_of_other_contexts` allows a reference into
    another context's `application.port.in` only from the origin context's `adapter.out`, so a use
    case or a controller naming another context's port fails it. Contexts also depend one way, held
    by `contexts_are_free_of_cycles`. Both live in `HexagonalArchitectureTest`. The rest stands.
  - **Amended 2026-10-31:** `configuration` is no longer exempt as a package.
    `only_the_wiring_sees_inside_a_context` lets only the `@Configuration` classes, and the classes
    nested in one, name another context's services and driven ports; any other class in
    `configuration` is held to the contexts' published driving ports. The rest stands.
- **Date:** 2026-08-30

## Context

[ADR-006](ADR-006-organise-packages-by-bounded-context.md) forbade **every** cross-context
reference, enforced by `contexts_do_not_depend_on_each_other`. That rule was written when the
contexts contained almost nothing, and modelling the real domain broke it twice in the first
hour:

- **Generating a roster needs workforce data.** Eligibility depends on qualifications and
  unavailability, which `workforce` owns. `scheduling` cannot do its job without them.
- **Every context needs the tenant identity.** `OrganisationId` and the `Actor` performing an
  operation appear in every use case in every context. They cannot belong to one context without
  every other context depending on it.

A rule that the real domain cannot satisfy does not survive; it gets worked around. The
available workarounds were worse than the problem: smuggling the dependency through
`configuration` (which is exempt), or duplicating identities per context so nothing can be
matched up reliably.

## Decision

Two mechanisms, both enforced.

**1. Contexts may reference another context's `application.port.in` package, and nothing else.**
Its domain, its services, its adapters and its driven ports stay private. This is a published
language: `workforce` exposes `FindRosterableEmployeesUseCase` returning `RosterableEmployee`,
a record built only from kernel types, and never its `Employee` aggregate.

`contexts_do_not_depend_on_each_other` is replaced by
`contexts_touch_only_published_ports_of_other_contexts`.

**2. A shared kernel holds identities and pure value types that cross contexts.**
`shared.domain.model` contains `OrganisationId`, `UserId`, `EmployeeId`, `QualificationId`,
`Role`, `Actor` with `TenantActor` and `Operator`, `TimeRange`, and their exceptions. It holds
no behaviour beyond what those values are, and **depends on no context** — enforced by
`the_shared_kernel_depends_on_no_context`.

**Consuming a published port happens in an adapter, not in the core.** `scheduling` declares its
own `WorkforceDirectory` driven port returning its own `RosterCandidate`, and one adapter
translates. That adapter is the single place where the cross-context allowance is spent, and the
scheduling domain and application layers never name `workforce` at all.

## Consequences

**Easier**

- The dependency the domain actually has is visible and enforced, rather than hidden in the
  composition root where no rule would examine it.
- Reshaping one context's aggregate cannot break another. `workforce` may restructure `Employee`
  freely as long as `RosterableEmployee` keeps its shape.
- Contexts share identities, so a `EmployeeId` from workforce and one in a scheduling assignment
  are the same type, and the compiler helps.

**Harder**

- **A loosened rule is a rule that has stopped protecting something.** This one now permits a
  reference it previously forbade, which is exactly when enforcement quietly becomes decoration.
  It was therefore watched failing on a `scheduling → workforce.domain` reference and watched
  permitting a published-port reference before being trusted.
- **The kernel is a coupling every context pays for.** It stays cheap only while it stays small
  and ignorant. Anything with behaviour or a domain opinion belongs in a context, and the
  temptation to put "just one more thing" in it is the failure mode to watch.
- **Translation is duplicated.** `RosterableEmployee` and `RosterCandidate` carry nearly the same
  fields, and an adapter maps between them. That redundancy is the price of the two contexts
  being able to change independently.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Keep the absolute ban and bridge in `configuration`** | Would satisfy the letter of ADR-006, because `@Configuration` classes are exempt. Rejected as the worst option: the dependency would be just as real, but hidden in the one place no rule inspects, and real translation logic would accrete in the composition root. |
| **Merge scheduling and workforce into one context** | Removes the problem by removing the boundary. Rejected because the boundary is genuine — rostering and personnel change for different reasons — and because it was chosen deliberately. |
| **Duplicate identity types per context** | Total independence, no kernel. Rejected because two `EmployeeId` types that must always agree are worse than one shared type: nothing would enforce the correspondence, and every boundary would need a conversion that could silently be wrong. |
| **Integrate by events rather than by query** | Would decouple further and suit a future split into services. Rejected as premature in one process with two contexts. **Revisit if** a context becomes a separate deployable, or if generation needs a read model too large to fetch per request. |
| **Put the whole shared kernel in an `access` context instead** | Tenancy would then be one context, which sounds tidier. Rejected because every other context would depend on it, which is a shared kernel wearing a disguise, and one with behaviour in it. |
