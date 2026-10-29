# ADR-051: Record why each frontend dependency is there

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

Every dependency needs a justification: what it replaces, its maintenance status, its licence, and
why the standard library or an existing dependency is not enough. The backend keeps that
justification as the comment beside each dependency in `backend/pom.xml`. The "External
dependencies and integration points" section of
[architecture.md](../architecture/architecture.md) repeats it in a table with a **Justification**
column, one row per backend dependency.

The frontend has no such place. `frontend/package.json` is JSON, and JSON cannot carry a comment.
Of the frontend's eighteen direct dependencies, the table had a row for one, `@vitest/coverage-v8`.
The decisions that do touch the others say little of them:

- [ADR-013](ADR-013-react-typescript-vite-frontend.md) chose React, TypeScript and Vite. It says
  nothing of their licence or maintenance, and it never names `@vitejs/plugin-react` or the
  `@types/*` packages.
- No ADR records the adoption of Prettier, `@eslint/js`, jsdom or the three `@testing-library/*`
  packages.
- ESLint, `eslint-plugin-react-hooks` and Vitest appear only for a version quirk, in ADR-013,
  [ADR-015](ADR-015-report-coverage-without-a-threshold.md),
  [ADR-043](ADR-043-defer-typescript-7-until-typescript-eslint-supports-it.md),
  [ADR-048](ADR-048-keep-a-browsers-tabs-on-one-session.md) and `frontend/CLAUDE.md`.

All but one of the sixteen devDependencies, and both dependencies, arrived in the first commit of
`frontend/` (`30cf15b`, 2026-08-30). Its message explains the version quirks it found and none of the
tooling choices. The exception, `@vitest/coverage-v8`, arrived with ADR-015.

## Decision

**We keep a frontend dependency's justification as its row in the dependency table of
`architecture.md`, beside the backend's.** A row says what the package is used for, who maintains it,
its licence, and why nothing already present is enough, and it links the ADR that chose it, if one
did. Adding, replacing or removing a frontend dependency changes its row in the same commit. A choice
between alternatives also gets an ADR, as any important decision does.

We write the rows for the seventeen dependencies that had none in the same change as this record.

This record holds only what is not a row:

- the reasons nobody wrote down, which the scan reconstructed;
- the constraints that bind the dependencies' versions to each other;
- the licence of the whole tree, as of 2026-10-29.

It names no exact version except where a pin matters, because the lockfile is the source of versions
and an ADR that copies them goes stale (ADR-015 names a Vitest version the lockfile no longer holds).

### Reasons nobody wrote down

The dependency scan, [#251](https://github.com/NaimElijah/HrSched/issues/251), reconstructed what
each package is for on 2026-10-29 from the manifest, the configuration files, the code, the npm
registry and the GitHub API. The rows say what a package does today, not what was said when it was
adopted. Where the reason for choosing one package over another was never written down, the row says
so and supplies none. Those choices are:

- **A framework at all.** ADR-013 weighs React against Svelte and Angular. It did not weigh going
  without one, nor Web Components, which browsers provide as Custom Elements and Shadow DOM.
- **`@vitejs/plugin-react`** rather than Vite's own handling of JSX.
- **Prettier** rather than another formatter. The ESLint config sets no formatting rule.
- **Vitest** rather than Jest, **jsdom** rather than `happy-dom`, and **the three Testing Library
  packages** rather than a root and `querySelector`, `fireEvent` and hand-written assertions.

The maintainer in a row is the organisation behind the package's source repository, from the npm
registry. None of those repositories is archived (checked with the GitHub API on 2026-10-29). The
licence is the `license` field of the package in `frontend/package-lock.json`.

### Constraints that bind these dependencies

- **`react`, `react-dom`, `@types/react` and `@types/react-dom` move together.** Their peer ranges
  are caret floors, not exact matches: `react-dom` peers `react` at `^` its own version, and
  `@types/react-dom` peers `@types/react` at `^` its own minor. The two failures differ. React
  throws at runtime unless `react` and `react-dom` are exactly the same version (#241). The peer of
  `@types/react-dom` makes `npm ci` fail with `ERESOLVE` when `@types/react` is bumped alone (#243).
  Dependabot opened them as pairs and both pull requests failed the frontend gate, so
  `.github/dependabot.yml` groups the four, as it groups Vitest.
- **`@vitest/coverage-v8` is pinned to Vitest's exact version** by peer dependency (ADR-015), and
  the `vitest` group in `.github/dependabot.yml` moves them together.
- **A Vite major waits until both `@vitejs/plugin-react` (`^8.0.0`) and Vitest admit it.** Vitest
  peers `vite` at `^6.4.0 || ^7.0.0 || ^8.0.0`.
- **TypeScript is held below 7** until `typescript-eslint` admits it as a peer (ADR-043).
- **`@eslint/js` follows ESLint's major.** It peers `eslint` at `^10.0.0`.

### The licence of the whole tree

Checked against `frontend/package-lock.json` on 2026-10-29.

- **The bundle.** Only `react`, `react-dom` and `scheduler` are not marked `dev` in the lockfile,
  and all three are MIT. Every other package is reachable only through a devDependency. The build
  writes the notices of what it bundles to `dist/.vite/license.md`. Nothing serves that file yet,
  which is left to the edge ([#162](https://github.com/NaimElijah/HrSched/issues/162)).
- **Weak copyleft and attribution in the dev tree.** `lightningcss` and its eleven platform
  binaries (all optional) are MPL-2.0, a weak copyleft, and come through `vite`. `caniuse-lite` is
  CC-BY-4.0, which asks for attribution, and comes through `browserslist` and `@babel/core` from
  `eslint-plugin-react-hooks`.
- **Other permissive or public-domain licences.** The lockfile also holds Apache-2.0 (including
  `typescript`), ISC, BSD-2-Clause, BSD-3-Clause, BlueOak-1.0.0, MIT-0 and CC0-1.0.
- **What is absent.** No GPL, AGPL, LGPL, EPL, SSPL or BSL appears, and no package lacks a licence.
- **Provenance.** The latest release of `react`, `react-dom`, `vite`, `@vitejs/plugin-react`,
  `vitest`, `@vitest/coverage-v8`, `jsdom`, `typescript-eslint` and the three Testing Library
  packages carried npm provenance on 2026-10-29. The latest release of `typescript`, `@types/*`,
  `eslint`, `@eslint/js`, `eslint-plugin-react-hooks` and `prettier` did not.

**Whether these licences are acceptable is open, and this record does not decide it.** `LICENSE`
reserves all rights and HrSched is meant to run as a hosted service, so the owner decides, in
[#280](https://github.com/NaimElijah/HrSched/issues/280). Nothing in CI checks licences: OSV-Scanner
([ADR-047](ADR-047-audit-dependencies-in-ci-with-osv-scanner.md)) audits advisories.

## Consequences

**What this makes easy.** A maintainer reads why a frontend dependency exists, who maintains it and
under what licence in one table, as for the backend. The row is a living document: it changes in the
commit that changes the dependency, which an append-only ADR cannot. The constraints that bind
versions are written down together, and the licence of the tree is stated with the one question it
leaves open, instead of discovered the day somebody asks.

**What this makes hard.**

- **The rows are kept by discipline.** Nothing fails a build when a dependency is added without a
  row. Review has to catch it, and `frontend/CLAUDE.md` says so.
- **A licence or maintainer can change unseen.** The tree's licence section here is a snapshot of
  2026-10-29, and no CI step checks licences or maintainers afterwards.
- **The reasons are reconstructed.** They describe what each package does today. For the choices
  listed above, the decision between alternatives is unrecoverable and is marked as such.
- **A choice between alternatives still costs an ADR**, with its file, index row and number. That is
  the check the rule asks for. A swap that chooses nothing between alternatives does not need one.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| A comment beside each dependency, as in `pom.xml` | JSON has no comments. A pseudo-comment key would be a workaround in a file that npm and Dependabot both rewrite. |
| An ADR holding the inventory of all eighteen (the first draft of this record) | An accepted ADR cannot be edited, so every row goes stale the day a dependency changes. ADR-015 already names a Vitest version the lockfile no longer holds. |
| One ADR for each dependency | Eighteen files and index rows, most of them restating the same four questions. An ADR is for a choice between alternatives. |
| A section in `frontend/CLAUDE.md` | It loads for agents and is kept under about 60 lines. It is guidance for working in `frontend/`, not the record. |
| Leave it, since ADR-013 covers the main choices | It chooses three of the eighteen, and names none of their licences. |

The choices below were made earlier and are recorded elsewhere. They are listed because they bear
on the set of dependencies:

| Alternative | Where it was weighed and rejected |
| --- | --- |
| Svelte, Angular, or server-rendered Thymeleaf | ADR-013 |
| A routing library; a mocking library | ADR-013: navigation is local state, and `fetch` is stubbed directly |
| Istanbul rather than v8 for coverage | ADR-015 |
| TypeScript 7 now, forced past `typescript-eslint`'s peer range | ADR-043 |
| Jest, `happy-dom`, a different formatter, a different Vite plugin | Not recorded at the time. |

**Revisit when** the owner decides #280 and refuses weak copyleft or attribution licences in the dev
tree, because `vite` and `eslint-plugin-react-hooks` then each need an answer. Revisit too when any
package other than `react`, `react-dom` and `scheduler` enters the production bundle, because the
statement about the bundle is then false. Supersede this record if the justification needs a home
other than the `architecture.md` table, for example if `package.json` gains a place for comments or
a CI check takes over the licence section.
