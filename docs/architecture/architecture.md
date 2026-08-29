# Architecture — HrSched

**Status: nothing to describe yet.** This document records the system **as it actually
is**, and as of the current commit that is an empty repository with a documentation
scaffold — no source, no build file, no runtime, no data store.

The headings below are therefore placeholders, not a plan. A plan for a system that does
not exist belongs in an [ADR](../ADRs/) as a *proposed* decision, never here: this file is
descriptive, never aspirational. For *what the system must do*, see
[requirements.md](../requirements/requirements.md).

Every section is blocked on the same thing — **ADR-001, the stack and module-boundary
decision, which has not been written**.

---

## Architecture overview

`TODO`. The shape of the system in a few sentences, once there is one.

## Components

`TODO`. One entry per component and what it is responsible for.

| Component | Responsibility |
| --- | --- |
| *none* | *no code exists yet* |

## Communication

`TODO`. How components talk: direct calls, HTTP, events, queues, shared storage. Record the
*style* and the reason, not just the mechanism.

## Data flow

`TODO`. The end-to-end path of a request or a record. For this domain the path worth
documenting first is roster publication: availability in → constraint evaluation → roster
persisted → notification out.

Diagrams live here as code (Mermaid, PlantUML, or Structurizr) — never as a pasted image,
because an image cannot be diffed, reviewed, or corrected by the next person.

## External dependencies and integration points

`TODO`. Databases, brokers, third-party APIs, identity providers, notification channels —
and what each is used for. Each new third-party dependency needs its justification recorded
before it is added: what it replaces, its maintenance status, its licence, and why the
standard library or an existing dependency is not enough.

| Dependency | Used for | Justification |
| --- | --- | --- |
| *none* | — | — |

## Structural decisions not visible from the file layout

`TODO`. The things a maintainer would otherwise have to reverse-engineer. At minimum, once
the first modules land, record:

- **Layering and dependency direction** — the domain must not import frameworks, I/O, or
  transport types; adapters depend on the core and never the reverse.
- **Module boundaries** — what each module owns, and what it is forbidden to reach into.
  No cyclic dependencies: a cycle means the boundary is wrong.
- **Error-handling policy** — where errors are enriched, where they are handled, and what
  is allowed to cross a boundary.
- **Validation boundary** — where untrusted input is checked, so that inside it the types
  can be trusted.
- **Values that must stay in sync across modules** — and what enforces that they do.
