# ADR-006: Organise packages by bounded context, not by layer

- **Status:** Accepted
  - **Amended 2026-10-30:** "Contexts may not depend on each other" is replaced by
    [ADR-008](ADR-008-context-integration.md): a context may reference another context's
    `application.port.in` package and nothing else. "No `shared/` package is created" is replaced
    by the same record, which adds the shared kernel `shared.domain.model`. The rest stands.
- **Date:** 2026-08-29

## Context

[ADR-003](ADR-003-hexagonal-architecture-with-ddd.md) established the hexagon but arranged it
**layer-first**: `domain/`, `application/`, `adapter/`, `configuration/` at the top. With a
single subject — shifts — that was the right shape and cost nothing.

It stops being right as soon as there is more than one subject:

- **A context is scattered.** Everything about shifts lives in five different top-level
  packages. Extracting a context into its own module or service means touching all of them.
- **Nothing detects a dependency between two contexts.** The cycle check slices by *layer*, so
  a scheduling class reaching into workforce internals is invisible to the build. That is the
  dependency most worth policing, and it was unpoliced.
- **The tree stops describing the business.** A newcomer sees `domain/`, which says only that
  the project uses DDD vocabulary, not what the system is about.

The move is cheapest now, at sixteen classes.

## Decision

We organise the backend **context-first**: each bounded context is a top-level package holding
its own complete hexagon.

```
io.github.naimelijah.hrsched
├── scheduling/          domain/ application/ adapter/
├── workforce/           domain/
├── configuration/       Spring wiring for every context
└── HrSchedApplication.java
```

**Contexts may not depend on each other.** Enforced by an ArchUnit rule alongside the existing
hexagon rules, so a cross-context reference fails the build rather than being caught in review.

`configuration/` and the composition root stay at the top level, as does the architecture test.

**No `shared/` package is created.** Nothing is shared by two contexts yet, and a package
created in anticipation becomes a dumping ground. It is added the first time two contexts
genuinely need the same type — and that will deserve its own ADR, because a shared kernel is a
coupling, not a convenience.

**`workforce` is created ahead of its requirements.** This is a deliberate exception to the
project rule against building on unanswered questions, taken at the repository owner's
direction, and it is recorded here rather than left implicit. The reason is that a rule saying
"contexts may not depend on each other" is **vacuous with one context** — it cannot be observed
failing, and an architecture rule that has never been seen to fail proves nothing. A second
context makes it real.

To limit the damage if the guess is wrong, `workforce` contains only an identity and a
non-blank name: no contracted hours, no qualifications, no employment status, no use cases, no
adapters. Every one of those depends on Q2 (roles) or Q4 (jurisdiction), which are open.

## Consequences

**Easier**

- A context is one directory. Extracting `scheduling` into its own module or deployable becomes
  a move, not an archaeology exercise.
- The package tree names the business rather than the pattern.
- The existing hexagon rules needed **no modification at all** — they match `..domain..`,
  `..application..` and `..adapter..`, which still match inside a context. Verified by
  reintroducing a Spring import into `scheduling/domain` and watching the build fail.
- `packages_are_free_of_cycles` now slices by *context* instead of by layer, which catches the
  more valuable cycle. It is **not** free: see the matching entry under *Harder*.

**Harder**

- **Extracting a context still means touching `configuration/`.** Its bean definitions live
  outside the context they wire, so "move one directory" is not quite the whole story. Keeping
  wiring central was chosen deliberately for now; per-context configuration is the natural
  correction when a context actually becomes a module.
- **A layer is no longer one place.** Reviewing "every adapter" or "every domain object" now
  means looking in each context. The ArchUnit rules handle this; human habit takes longer.
- **`workforce` is unvalidated.** If Q2 or Q4 land differently, `Employee` may be wrong, or may
  belong to a differently drawn context. It is small on purpose so deleting it is cheap.
- With two contexts the cross-context rule is real but thin. It grows more valuable as contexts
  multiply, which is also when it is hardest to introduce.
- **Re-slicing the cycle check cost real coverage.** Slicing by context means a cycle *within* a
  context — `scheduling.domain` depending back on `scheduling.application` — no longer registers,
  because both are inside one slice. Confirmed empirically: such a cycle leaves
  `packages_are_free_of_cycles` green. A second rule, `layers_within_a_context_are_free_of_cycles`,
  restores that coverage by slicing one level deeper.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Keep layer-first, nest contexts inside each layer** (`domain/scheduling/`, `application/service/scheduling/`) | The smallest diff, and the most literal reading of "organise the domain by contexts". Rejected because a context stays scattered across five packages and the cycle check keeps slicing by layer — so the cross-context dependency, the thing worth catching, still goes undetected. |
| **Contexts in the core only, adapters grouped by technology** | Keeps all web code together, which reads nicely. Rejected because extracting a context would still mean picking its controllers and repositories out of shared adapter packages, losing most of the benefit. |
| **Spring Modulith** for context enforcement | Purpose-built for exactly this, with richer verification and documentation generation. Rejected as a dependency whose value does not yet exceed four ArchUnit rules we already have and already trust. **Revisit if** contexts reach roughly four or more, or if cross-context communication moves to events. |
| **A test-only fixture instead of a real `workforce` context** | Evaluating the rule against a synthetic class set under `src/test` would prove it can fail, permanently and as a regression guard, without adding production code that no requirement asks for and nothing references. This is the stronger engineering answer and was recommended. It was **not** taken: the repository owner chose a real second context. Recorded here so the trade is visible rather than implied. **Revisit if** `workforce` is still unreferenced when Q2 and Q4 are answered. |
| **Wait for the context map before restructuring** | Boundaries drawn from answered requirements would be better grounded. Rejected because the move is cheapest now and the first boundary — scheduling — is not in doubt; redrawing later is a rename, not a rewrite. |
