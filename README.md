<div align="center">

# HrSched

**Staff shift scheduling — turning shifts that need covering and people with constraints into a roster that is actually legal to work.**

![status](https://img.shields.io/badge/status-pre--implementation-lightgrey?style=flat-square)
![licence](https://img.shields.io/badge/licence-proprietary-lightgrey?style=flat-square)

[Requirements](docs/requirements/requirements.md) · [Architecture](docs/architecture/architecture.md) · [Decisions](docs/ADRs/)

</div>

---

> [!WARNING]
> **There is no code in this repository yet** — no source, no build file, no toolchain, no
> tests. What exists is the documentation scaffold below. Sections marked *not written yet*
> are blocked on the stack decision, [ADR-001](docs/ADRs/), which has not been made.

This README is ordered for a **contributor**, not an evaluator: there is nothing to
evaluate, so the map of the repository comes before the (empty) instructions for running it.
Once the system runs, move *Quick start* above *Architecture*.

## Contents

- [What and why](#what-and-why)
- [Documentation](#documentation)
- [Project structure](#project-structure)
- [Features](#features) · [Tech stack](#tech-stack) · [Architecture](#architecture)
- [Quick start](#quick-start) · [Configuration](#configuration) · [Usage](#usage)
- [Development](#development) · [Testing](#testing) · [Deployment and operations](#deployment-and-operations)
- [Design decisions](#design-decisions)
- [Status and limitations](#status-and-limitations)
- [Contributing](#contributing) · [Licence](#licence)

## What and why

Rostering shift work by hand collapses under its own constraints. A scheduler juggling
availability, qualifications, rest periods, and contracted hours across a few dozen people
will produce a roster that looks finished and is quietly illegal — and the error surfaces on
the shop floor at 04:00, not in the spreadsheet.

HrSched takes the shifts that need covering and the people available to cover them, and
produces a roster that respects those constraints, then keeps it correct as sickness, swaps,
and cancellations arrive. The value is in the constraint checking; the calendar is the easy
part.

What it deliberately does *not* do is not yet settled — see
[Non-goals](docs/requirements/requirements.md#non-goals), currently an open question rather
than an answer.

## Documentation

| Document | Answers |
| --- | --- |
| [`docs/requirements/requirements.md`](docs/requirements/requirements.md) | **What** the system must do — capabilities, domain rules, non-functional constraints, non-goals |
| [`docs/architecture/architecture.md`](docs/architecture/architecture.md) | **How** it is built — components, communication, data flow, dependency direction |
| [`docs/ADRs/`](docs/ADRs/) | **Why** each solution was chosen, what it costs, and what was rejected |
| [`.claude/CLAUDE.md`](.claude/CLAUDE.md) | Repository-specific guidance for coding agents |

## Project structure

```
.
├── .claude/                 # Agent configuration, tracked in git
│   ├── CLAUDE.md            # Repo-specific guidance for Claude Code
│   ├── agents/              # (empty)
│   ├── rules/               # (empty)
│   └── skills/              # (empty)
├── docs/
│   ├── ADRs/                # Decision records, index, and template
│   ├── architecture/        # The system as it actually is
│   └── requirements/        # What the system must do
├── .editorconfig            # Language-agnostic editor settings
├── .gitignore
├── LICENSE
└── README.md
```

Source and test roots are not listed because they do not exist. Add them here in the same
commit that creates them.

## Features

*Not written yet.* Will list 5–8 things a user or caller can actually do, each traceable to a
row in [Functional requirements](docs/requirements/requirements.md#functional-requirements) —
which is itself still empty. A feature listed here and absent from the code is a bug report
waiting to be filed.

## Tech stack

*Not written yet — blocked on [ADR-001](docs/ADRs/) (language, runtime, framework).* Will be
a table of layer / technology / **pinned** version / one-line rationale, matching the
lockfile.

## Architecture

*Not written yet.* A container diagram in Mermaid and a walkthrough of the main request path
go here, summarising [`docs/architecture/architecture.md`](docs/architecture/architecture.md)
rather than duplicating it. Diagrams are committed as code, never as pasted images, and carry
no hardcoded colours so they survive both GitHub themes.

## Quick start

*Not written yet — blocked on [ADR-001](docs/ADRs/).* Will hold: prerequisites with exact
versions, `git clone`, **one** setup command, **one** run command, and the URL the system
answers on — plus seed data and local credentials. Every command must be the same one CI
runs; a README-only variant drifts the day someone edits the pipeline.

## Configuration

*Not written yet.* Will be a table of environment variable / purpose / default / required,
mirroring a committed `.env.example` that carries keys and no values. Secrets never enter the
repository, the logs, or error messages.

## Usage

*Not written yet.* Two or three real examples with actual request and response bodies —
copied from a working system, never invented.

## Development

Conventions that already apply, because they do not depend on the stack:

- **Commits** — [Conventional Commits](https://www.conventionalcommits.org/):
  `type(scope): imperative subject`, under 72 characters. The body explains *why* and
  references the issue or ADR. One logical change per commit; never a refactor mixed with a
  behaviour change.
- **Branches** — `type/short-kebab-description`. Never commit straight to `main`.
- **Documentation** — a change to behaviour and the documentation describing it ship in the
  same commit.
- **Dependencies** — every new third-party dependency needs a recorded justification: what it
  replaces, its maintenance status, its licence, and why the standard library or an existing
  dependency is not enough.

*Not written yet:* local setup without containers, migrations, and the end-to-end recipe for
adding a feature — all blocked on [ADR-001](docs/ADRs/).

## Testing

The naming convention is settled: `Given<input/scenario>_When<action>_Then<result>`. Tests
run against the public boundary — the port, the API, the CLI — never against internals, so
that refactoring stays possible. They are deterministic: injected clock, seeded randomness,
no `sleep`, no real network, no dependence on ordering.

*Not written yet:* the test layers, the command for each, coverage reporting, and what CI
gates on — blocked on [ADR-001](docs/ADRs/).

## Deployment and operations

*Not written yet — blocked on [Q5](docs/requirements/requirements.md#open-questions) (where
this runs).* Will cover environments, build artefacts, migration strategy, health and
readiness endpoints, metrics, traces, and the log format.

## Design decisions

No decisions have been recorded yet. Three are known-open and blocking: **ADR-001** (language,
runtime, framework), **ADR-002** (module boundaries and dependency direction), and **ADR-003**
(data storage and schema strategy) — see the [ADR index](docs/ADRs/).

## Status and limitations

**Maturity: pre-implementation.** The repository holds documentation scaffolding and nothing
else.

**Shipped:** nothing. **Next:** answer the
[open questions](docs/requirements/requirements.md#open-questions), then write ADR-001.
Implementation is blocked until then — the stack is a decision, not a side effect of the first
file someone writes.

Deliberately not in the repository yet, because their content is entirely determined by a
stack that does not exist:

| Missing | Unblocked by |
| --- | --- |
| One-command setup / run / test (`Makefile` or `justfile`) | ADR-001 |
| CI pipeline (build, test, lint, format check, dependency audit) | ADR-001 |
| Pinned toolchain versions, formatter and linter config | ADR-001 |
| `CHANGELOG.md` | The first release |

## Contributing

No `CONTRIBUTING.md` yet. Until there is one, the conventions in [Development](#development)
and [`.claude/CLAUDE.md`](.claude/CLAUDE.md) are the standard.

## Licence

Proprietary — **all rights reserved**. See [`LICENSE`](LICENSE). No permission is granted to
use, copy, modify, or distribute this software.
