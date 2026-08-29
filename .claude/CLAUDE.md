# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

This repository holds **documentation only** — no source, no build file, no toolchain, no
tests. Nothing below is inferred from code, because there is no code to infer from.

The domain is settled — HrSched is a staff shift scheduling (rostering) system — but
nothing beyond that purpose is. Read `docs/requirements/requirements.md` before proposing
work; most of it is still `TODO`, and a `TODO` there is a question for the repo owner, not
a gap for an agent to fill in.

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

- `docs/requirements/requirements.md` — what the system must do. Intent, not observation.
- `docs/architecture/architecture.md` — the system as it actually is. Descriptive, never
  aspirational; currently describes nothing, because nothing exists.
- `docs/ADRs/` — one file per decision, `ADR-NNN-short-kebab-title.md`, append-only.
  `README.md` is the index and the list of decisions still owed; `adr-template.md` is the
  starting point for a new record.
- `.claude/skills/`, `.claude/agents/`, `.claude/rules/` — agent configuration, tracked in git.

TODO: add source and test roots when they exist.

## Conventions

None are recorded here yet, deliberately: a convention belongs in this file only once it
is observable in the code. When the first modules land, document what an agent cannot see
from a single file — layering and dependency direction, error-handling policy, migration
rules, and any value that must stay in sync across modules.
