<!--
Maintainers: this file is loaded into every session and every subagent, so it holds only what
applies everywhere: commands, workflow and the rules that cross both projects. Keep it under about
130 lines. Backend-only guidance goes in backend/CLAUDE.md, frontend-only guidance in
frontend/CLAUDE.md; each loads the first time a file beneath it is read or edited. Facts about one
machine belong in auto memory, not here. This comment is stripped before Claude sees the file.
-->

# CLAUDE.md

HrSched generates and publishes staff rosters for many organisations at once. The domain is
modelled and works end to end: sign in, define people and shifts, configure working-time rules,
generate a roster, publish it. Four contexts — `scheduling`, `workforce`, `access`,
`notifications` — plus a `shared` kernel, with the hexagon and the context boundaries enforced by
build-failing tests.

## Rules that hold everywhere

- **Multi-tenant.** Every aggregate belongs to exactly one organisation and every query is
  scoped to it. A cross-tenant read is a security incident, not a bug.
- **No number from any statute is ever written down** — not in a class, not in a migration, not
  as the default of a form field. Working-time rules are a *configured constraint set*: a rest
  period or an hours cap is a value in a `ConstraintSet`, and the schema stores a limit without
  ever defaulting one. A jurisdiction is a preset whose numbers come from the statute, with the
  source cited. Test values are invented on purpose, and are labelled as such.
- **Constraints are invariants.** A roster violating its constraint set cannot be published,
  and there is no override.
- **Who is acting comes from the access token and nothing else.** HrSched holds its own accounts
  and signs its own tokens (ADR-011, ADR-028); the organisation comes from the acting account,
  never from a header, a path or a body (ADR-031). **`SecurityConfiguration` ends in `denyAll`**
  (ADR-032).
- **Never let unverifiable data pass a check.** If evidence a rule needs cannot be loaded, refuse
  — a skipped assignment is a roster published with hours nobody counted.
- **NEVER log personal data.** Names, absence and contracted hours say things about a person's
  health and household, and a log is copied to places a database is not.
- **New code belongs to a context.** If it fits none of the existing ones, that is a new
  bounded context and a new ADR, not a new top-level package.
- **Do not add an ORM.** Persistence is hand-written SQL against PostgreSQL (ADR-004).

How the backend enforces each of these is in `backend/CLAUDE.md`.

## Two things are not what they look like

- **Generation is a solver, in the background.** `CpSatRosterGenerator` (ADR-044) fills every
  place the rules allow, then minimises ADR-010's weighted score, each search stopped at
  `hrsched.generation.time-limit-per-phase`: at the stated scale that is the best found, not proven
  optimal. `POST /api/rosters/generations` answers 202, the solve runs on `GenerationRunner`'s
  threads, and the generation is a row any instance reads back (ADR-046). It runs outside any
  transaction (ADR-045) and is not deterministic.
  OR-Tools is held at 9.12, because later Windows libraries crash the JVM on the Visual C++
  runtime JDK 21 bundles.
- **The audit trail is a log stream, not a trail.** Every change and refusal is recorded through
  an `AuditTrail` driven port, but nothing is stored and nothing is queryable: the record lives
  only wherever stdout is collected (ADR-018). The requirement is now a durable trail an Admin
  reads for their own organisation (Q20, #31); how long it is kept is Q23.

## Requirements and decisions

Many rows of `docs/requirements/requirements.md` say `not yet`: the requirement is decided and an
issue tracks building it, so the code is behind the document on purpose. What is still `TODO`
there, and every open question, is a question for the repo owner, not a gap for an agent to fill
in. Do not invent a requirement, a domain rule, or a persistence choice to make a document look
complete.

A new ADR takes the highest number in **either** table of `docs/ADRs/README.md`, plus one — never
a gap. The gaps (today 035, 036) are reserved under "Decisions waiting to be recorded". The
index row lands in the same commit as the ADR.

## Git

- Branch `type/short-kebab-description` off `dev`, and open the pull request **into `dev`**. `dev`
  reaches `main` only through a pull request of its own, so a feature branch never targets
  `main`, whatever a tool assumes the default branch is.
- Commits follow Conventional Commits, `type(scope): subject`, one logical change each.
- CI runs on every push, and on pull requests to `main` and `dev`.

## Commands

Two buildable projects: `backend/` (Maven Wrapper, no global Maven needed) and `frontend/`
(npm). The repository root builds nothing itself but orchestrates both (ADR-014).

From the **root**: `npm run setup` · `npm run dev` · `npm run verify` (runs both gates) ·
`npm run verify:backend` / `npm run verify:frontend` (one gate).

From `backend/`:

- Build + test + format + architecture (**the whole gate; what CI runs**): `./mvnw verify`
- Run: `./mvnw spring-boot:run` — serves on `http://localhost:8080`
- Test (all): `./mvnw test`
- Test (single class): `./mvnw spotless:apply test -Dtest=HexagonalArchitectureTest`. Spotless's
  check is bound to `validate`, so without the `spotless:apply` a formatting slip fails the run
  before any test runs. For a deliberate mutation that Spotless would rewrite, pass
  `-Dspotless.check.skip=true` instead.
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

**Docker must be running to build.** The tests use a real PostgreSQL through Testcontainers, one
container shared by the suite. There is no in-memory fallback and no skip-when-Docker-is-absent, on
purpose: a check that quietly does not run still reports green.

**Read the log, not the exit code.** `mvnw.cmd`, which `npm run verify` and `npm run dev` call on
Windows, has exited 0 and has exited 1 when Maven never started. A run has passed only when its log
shows `Tests run:` and `BUILD SUCCESS`. From a POSIX shell, use `./mvnw`.

**Local sign-in.** `npm run dev` and `./mvnw spring-boot:run` bootstrap the operator `operator` /
`local-development-only`, whose password must be changed at first sign-in (ADR-029). The compose
database keeps its data between runs, so from then on only the changed password works;
`docker compose -f backend/compose.yaml down -v` starts over, deleting every local row.

## Layout

Repository root: `backend/`, `frontend/`, `docs/`, `scripts/`, `package.json`, `LICENSE`,
`README.md`. The two projects build independently (ADR-007); `scripts/run.mjs` only orchestrates
and must never accumulate build logic (ADR-014).

Outside `backend/`:

- `docs/requirements/`, `docs/architecture/`, `docs/ADRs/` — what, how, and why.
- `.github/workflows/ci.yml` — three jobs: backend and frontend, each running that project's own
  verify command, and a dependency audit of both (ADR-047). **No path filters:** a wrongly
  filtered check is skipped silently and still reports green.

Guidance for each project lives beside it, and loads the first time a file beneath it is read or
edited: `backend/CLAUDE.md` (the hexagon, persistence, testing, Spring Boot 4) and
`frontend/CLAUDE.md` (the SPA's layout and what the UI may not do).
