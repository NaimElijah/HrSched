# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

This repository is **empty** — no source, no build file, no toolchain, no tests. The only
commit is `a97312f temp init`. Nothing below is inferred from code, because there is no
code to infer from.

Consequence for agents: do not describe, assume, or extend an architecture here. Before
writing implementation code, the stack and the module boundaries have to be decided, and
that decision is an ADR (`docs/ADRs/ADR-001-…`), not an implicit side effect of the first
file you write.

## Commands

TODO: none exist yet. Fill in as soon as a build file lands, with the real invocations:

- Build:
- Run:
- Test (all):
- Test (single):
- Lint / format check:

## Layout

- `docs/ADRs/` — one file per decision, `ADR-NNN-short-kebab-title.md`, append-only.
- `docs/requirements/` — specifications.
- `docs/architecture/` — architecture notes and diagrams-as-code.
- `.claude/skills/`, `.claude/agents/`, `.claude/rules/` — agent configuration, tracked in git.

TODO: add source and test roots when they exist.

## Conventions

None are recorded here yet, deliberately: a convention belongs in this file only once it
is observable in the code. When the first modules land, document what an agent cannot see
from a single file — layering and dependency direction, error-handling policy, migration
rules, and any value that must stay in sync across modules.
