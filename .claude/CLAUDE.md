<!--
Maintainers: this file is loaded into every session and every subagent, so it holds only what
applies everywhere: commands, workflow and the rules that cross both projects. Keep it under about
130 lines. Backend-only guidance goes in backend/CLAUDE.md, frontend-only guidance in
frontend/CLAUDE.md; each loads the first time a file beneath it is read or edited. Facts about one
machine belong in auto memory, not here. This comment is stripped before Claude sees the file.
-->

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

The domain is modelled and works end to end: sign in, define people and shifts, configure
working-time rules, generate a roster, publish it. Four contexts — `scheduling`, `workforce`,
`access`, `notifications` — plus a `shared` kernel, with the hexagon and the context boundaries
enforced by build-failing tests.

**Authentication is real, and the header placeholder is gone.** HrSched holds its own accounts and
signs its own tokens (ADR-011, ADR-028). Every controller takes the acting identity from
`@AuthenticationPrincipal Actor`, and the organisation from that actor through each adapter's
`actingIn` helper — never from a header, a path or a body (ADR-031). `X-User`, `X-Role`,
`X-Organisation` and `X-Employee` are read by nothing; do not reintroduce them.
**`SecurityConfiguration` ends in `denyAll`** (ADR-032), so a new route is unreachable until it is
named there — that second edit is the decision about who may call it, not a formality. The URL
rules are the coarse outer line; the use case behind each route is what decides.

Two things are **not** what they look like:

- **Generation is greedy, not optimised.** Automatic generation is the point of the product and
  a real implementation is a solver; `GreedyRosterGenerator` exists to prove the port (ADR-005).
  It ignores fairness, preferences and disruption entirely.
- **The audit trail is a log stream, not a trail.** Every change and refusal is recorded through
  an `AuditTrail` driven port, but nothing is stored and nothing is queryable: the record lives
  only wherever stdout is collected (ADR-018). The requirement is now a durable trail an Admin
  reads for their own organisation (Q20, #31); how long it is kept is Q23.

`docs/requirements/requirements.md` was reviewed row by row with the repo owner on 2026-09-22.
Many rows say `not yet`: the requirement is decided and an issue tracks building it, so the code
is behind the document on purpose. What is still `TODO` there, and every open question, is a
question for the repo owner, not a gap for an agent to fill in. Do not invent a requirement, a
domain rule, or a persistence choice to make a document look complete.

Every question that blocked modelling is answered — see `docs/requirements/requirements.md`.
Three consequences bind everything written from now on:

- **Multi-tenant.** Every aggregate belongs to exactly one organisation and every query is
  scoped to it. A cross-tenant read is a security incident, not a bug.
- **No legal threshold is ever hardcoded.** Working-time rules are a *configured constraint
  set*; a jurisdiction is a preset, and a preset's numbers must come from the statute with the
  source cited. Never write a rest period or an hours cap as a constant.
- **Constraints are invariants.** A roster violating its constraint set cannot be published,
  and there is no override.

**Docker must be running to build.** The tests use a real PostgreSQL through Testcontainers, one
container shared by the suite. There is no in-memory fallback and no skip-when-Docker-is-absent, on
purpose: a check that quietly does not run still reports green.

## Commands

Two buildable projects: `backend/` (Maven Wrapper, no global Maven needed) and `frontend/`
(npm). The repository root builds nothing itself but orchestrates both (ADR-014).

From the **root**: `npm run setup` · `npm run dev` · `npm run verify` (runs both gates).

- Build + test + format + architecture (**the whole gate; what CI runs**): `./mvnw verify`
- Run: `./mvnw spring-boot:run` — serves on `http://localhost:8080`
- Test (all): `./mvnw test`
- Test (single class): `./mvnw test -Dtest=HexagonalArchitectureTest`
- Format check / fix: `./mvnw spotless:check` / `./mvnw spotless:apply`
- Coverage: written by `./mvnw verify` to `target/site/jacoco/index.html`. `./mvnw test` alone
  writes none — the report goal binds to `verify`. Nothing fails on it (ADR-015).

From `frontend/`:

- Everything CI runs: `npm run verify` — format check, lint, typecheck, tests, production build
- Dev server: `npm run dev` — serves on 5173 and proxies `/api` to 8080
- One check at a time: `npm run lint` / `npm run typecheck` / `npm test`
- Coverage: `npm run test:coverage` → `coverage/index.html`. Deliberately not part of `verify`.

On Windows without a POSIX shell, use `mvnw.cmd`; it is a batch/PowerShell polyglot, so PowerShell
must be on `PATH`. Add `-B` for non-interactive output.

## Layout

Repository root: `backend/`, `frontend/`, `docs/`, `scripts/`, `package.json`, `LICENSE`,
`README.md`. The two projects build independently (ADR-007); `scripts/run.mjs` only orchestrates
and must never accumulate build logic (ADR-014).

Outside `backend/`:

- `docs/requirements/`, `docs/architecture/`, `docs/ADRs/` — what, how, and why.
- `.github/workflows/ci.yml` — two jobs, backend and frontend, each running that project's own
  verify command. **No path filters:** a wrongly filtered check is skipped silently and still
  reports green.

Guidance for each project lives beside it, and loads the first time a file beneath it is read or
edited: `backend/CLAUDE.md` (the hexagon, persistence, testing, Spring Boot 4) and
`frontend/CLAUDE.md` (the SPA's layout and what the UI may not do).

## Conventions

- **Never let unverifiable data pass a check.** If evidence a rule needs cannot be loaded, refuse
  — a skipped assignment is a roster published with hours nobody counted.
- **New code belongs to a context.** If it fits none of the existing ones, that is a new
  bounded context and a new ADR, not a new top-level package.
- **NEVER log personal data.** Names, absence and contracted hours say things about a person's
  health and household, and a log is copied to places a database is not. `AuditedChange` carries a
  bare `UUID` subject so a name is *unrepresentable*, not merely discouraged, and
  `SchedulingAuditLogTest`/`WorkforceAuditLogTest` assert every field is an identifier or an enum.
  Attach fields with slf4j `addKeyValue`, never by interpolating them into the message: the message
  is a constant, and everything variable is a field the ECS encoder publishes as JSON.
