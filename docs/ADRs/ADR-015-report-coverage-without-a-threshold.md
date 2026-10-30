# ADR-015: Report test coverage on both projects, and never enforce a threshold

- **Status:** Accepted
  - **Amended 2026-10-30:** "Vitest 4.1.11" has moved. The version in use, and the matching
    `@vitest/coverage-v8`, are in `frontend/package.json`.
- **Date:** 2026-08-30

## Context

The backend gate is `./mvnw verify` and the frontend gate is `npm run verify`
([ADR-002](ADR-002-single-command-build-gate.md), [ADR-014](ADR-014-root-task-runner.md)); CI runs
exactly those two commands and nothing else. Neither has ever measured which code the suite
reaches.

That was tolerable while the suite was small enough to hold in one's head. It stopped being so when
the backend went from 126 tests to roughly four times that in a single branch, and the gaps that
work closed were found by reading every class and asking what had no test — a method that does not
survive the next contributor.

Three facts constrain what can be adopted:

- **Neither `spring-boot-dependencies` nor `spring-boot-starter-parent` 4.1.1 manages a JaCoCo
  version.** Both were read; there is no match. A version has to be declared and then maintained.
- **`@vitest/coverage-v8` is pinned by peer dependency to the exact Vitest version.** Vitest 4.1.11
  requires coverage 4.1.11. The two move together or `npm ci` fails.
- **Some of the most important code here will always score badly.** `HrSchedApplication.main`,
  `UseCaseConfiguration`, and the record accessors Jackson calls reflectively are not going to be
  covered by tests worth writing.

## Decision

We measure coverage on both projects and report it. We set no threshold, and nothing fails a build
because of it.

Backend: `jacoco-maven-plugin`, version pinned in `pom.xml`, `prepare-agent` at `initialize` and
`report` at `verify`. **No `check` goal and no `<rules>`.** HTML and XML land in
`target/site/jacoco/`.

Frontend: `@vitest/coverage-v8` pinned to the installed Vitest. Vitest leaves coverage `enabled:
false` by default, so the configuration block only takes effect under `--coverage`, exposed as
`npm run test:coverage`. **`npm run verify` is unchanged and prints no coverage table.**

Coverage is a map of where to look next. It is read by a person, not by CI.

## Consequences

**What this makes easy.** The unexercised paths are now visible rather than remembered. The first
run already earned its place: it put numbers on gaps that had been guessed at, and named files at
0% that no one had listed. Neither gate got slower or more fragile — the frontend gate runs no
coverage at all, and JaCoCo adds one JVM argument to a backend suite whose runtime is dominated by
two Spring context startups.

**What this makes hard.** A report nobody opens changes nothing, so this buys a diagnostic and not
a guarantee. A pinned JaCoCo version is a version to maintain, and `@vitest/coverage-v8` must be
bumped in lockstep with Vitest or `npm ci` fails outright on the peer. And the honest cost: **a
number that is not enforced is a number that can quietly fall**, with nothing but review standing
in the way.

One trap is worth recording because it fails silently. `prepare-agent` sets the `argLine` property
that Surefire reads. Nothing configures `argLine` today, but anyone who adds one must write
`@{argLine}` into it — otherwise coverage drops to zero and the build stays green. A pom comment
says so at the point of danger.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| A build-failing minimum, say 80% | A threshold turns a diagnostic into a target, and the cheapest way to hit a coverage target is a test with no assertions. This repository already refuses gates that can be satisfied without doing the work — `ci.yml` says so about path filters, `run.mjs` about mistyped task names. **Revisit if** the report goes unread for a release cycle; a low floor that catches only a collapse would then beat nothing. |
| `jacoco:check` with rules but `haltOnFailure=false` | A check that cannot fail is a check nobody reads, plus configuration to maintain. Strictly worse than the report alone. |
| Istanbul rather than v8 on the frontend | v8 needs no instrumentation step and is the Vitest default. Istanbul's edge is more precise branch attribution through transpilation, which is worth having only once someone acts on a branch number. **Revisit if** remapped v8 line attribution is observed to mislead. |
| Uploading coverage to a hosted service | A third-party integration and a token, added to a private repository that is not deployed anywhere, to display a number nothing gates on. **Revisit if** the repository becomes public, where a badge has an audience. |
| Coverage in CI only, not locally | The drift [ADR-002](ADR-002-single-command-build-gate.md) exists to prevent: a check that runs somewhere the developer does not is a check the developer meets by failing. |
| No coverage tooling at all | The status quo, and the reason the gaps this branch closed had to be found by hand. |

**Revisit trigger for the decision as a whole:** the first defect that ships through a path everyone
assumed was covered. That is the evidence that reading the report is not happening, and enforcement
would then be buying something real.
