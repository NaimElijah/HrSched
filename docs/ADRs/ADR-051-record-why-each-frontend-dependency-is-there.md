# ADR-051: Record why each frontend dependency is there

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

Every dependency needs a justification: what it replaces, its maintenance status, its licence, and
why the standard library or an existing dependency is not enough. The backend keeps that
justification as the comment beside each dependency in `backend/pom.xml`, and the "External
dependencies" section of [architecture.md](../architecture/architecture.md) repeats it for the
backend's.

The frontend has no such place. `frontend/package.json` is JSON, and JSON cannot carry a comment.
The decisions that do touch the frontend's dependencies say little of it:

- [ADR-013](ADR-013-react-typescript-vite-frontend.md) chose React, TypeScript and Vite. It says
  nothing of their licence or maintenance, and it never names `@vitejs/plugin-react` or the
  `@types/*` packages.
- No ADR records the adoption of Prettier, `@eslint/js`, jsdom or the three `@testing-library/*`
  packages.
- ESLint, `eslint-plugin-react-hooks` and Vitest appear only for a version quirk, in ADR-013,
  [ADR-015](ADR-015-report-coverage-without-a-threshold.md),
  [ADR-043](ADR-043-defer-typescript-7-until-typescript-eslint-supports-it.md),
  [ADR-048](ADR-048-keep-a-browsers-tabs-on-one-session.md) and `frontend/CLAUDE.md`.

So for most of the frontend's eighteen direct dependencies, nothing in the repository says why it is
there.

This record fills that gap after the fact. All but one of the sixteen devDependencies, and both
dependencies, arrived in the first commit of `frontend/` (`30cf15b`, 2026-08-30). Its message
explains the version quirks it found and none of the tooling choices. The exception,
`@vitest/coverage-v8`, arrived with ADR-015. The reasons below were therefore **reconstructed on
2026-10-29 by the dependency scan, [#251](https://github.com/NaimElijah/HrSched/issues/251)**, from
the manifest, the configuration files, the code and the npm registry. They say what each package is
for today. Where the reason a package was chosen over another was never written down, the table says
"not recorded at the time" and does not supply one.

## Decision

We record the justification of every direct frontend dependency here, and we require a new ADR for
each dependency added later. The table is the record; it is a snapshot of 2026-10-29. It names no
exact version except where a pin matters, because the lockfile is the source of versions and an ADR
that copies them goes stale (ADR-015 names a Vitest version the lockfile no longer holds).

"Maintained by" is the organisation or team behind the package's source repository, from the npm
registry. None of the repositories is archived (checked with the GitHub API on 2026-10-29). Licences
are the `license` field of each package in `frontend/package-lock.json`.

### In the production bundle

Two direct dependencies, and `scheduler`, which `react-dom` brings. All three are MIT.

| Package | What it does | Maintained by | Licence | Why not the platform or something already here |
| --- | --- | --- | --- | --- |
| `react` | The component model the screens in `src/pages` are written in. | The React team (Meta) | MIT | The browser has no component model. ADR-013 weighs React against Svelte and Angular, and not against going without a framework. |
| `react-dom` | Renders React into the page: `createRoot` in `src/main.tsx`. | The React team (Meta) | MIT | `react` is renderer-independent, and this is its browser renderer. Its peer range requires the matching `react`. |

### In the build and the types (devDependencies)

| Package | What it does | Maintained by | Licence | Why not the platform or something already here |
| --- | --- | --- | --- | --- |
| `vite` | The dev server, with the `/api` proxy to port 8080 (ADR-013), and `vite build`. | The Vite organisation | MIT | Neither Node nor the browser bundles TypeScript and JSX, or proxies a dev server. |
| `@vitejs/plugin-react` | The React plugin, enabled in `vite.config.ts`. Never named in ADR-013. | The Vite organisation | MIT | Why it, rather than Vite's built-in handling of JSX, is not recorded at the time. The package calls itself the default Vite plugin for React. |
| `typescript` | The compiler. `npm run typecheck` runs `tsc --noEmit`. | Microsoft | Apache-2.0 | ADR-013 calls it the load-bearing half of its choice: a backend contract change becomes a compile error. JavaScript has no static types. |
| `@types/react`, `@types/react-dom` | The type declarations for `react` and `react-dom`. | DefinitelyTyped | MIT | Neither package publishes its own types (`npm view react types` is empty). |

### In linting and formatting (devDependencies)

| Package | What it does | Maintained by | Licence | Why not the platform or something already here |
| --- | --- | --- | --- | --- |
| `eslint` | The linter behind `npm run lint`, configured in `eslint.config.js`. | The OpenJS Foundation | MIT | `tsc` does not enforce the rules the config sets: no `console`, no explicit `any`. |
| `@eslint/js` | ESLint's recommended rule set, used as `js.configs.recommended`. | The ESLint project | MIT | ESLint turns no rule on by itself, and this package is where its recommended set lives. Its peer range requires ESLint 10. |
| `typescript-eslint` | Lets ESLint parse TypeScript, and supplies the `@typescript-eslint/*` rules the config sets. | The typescript-eslint organisation | MIT | ESLint cannot parse TypeScript alone. Its peer range is why TypeScript stays below 7 (ADR-043). |
| `eslint-plugin-react-hooks` | The rules of hooks, and the stricter checks of version 7. | The React team | MIT | No other package carries these rules. The first commit of `frontend/` records that they caught a real defect: `useAsync` set state synchronously inside an effect. |
| `prettier` | The formatter behind `npm run format:check`, a step of `npm run verify`. Configured in `.prettierrc.json`. | The Prettier organisation | MIT | The ESLint config sets no formatting rule, so nothing else formats. Why Prettier over another formatter is not recorded at the time. |

### In testing (devDependencies)

| Package | What it does | Maintained by | Licence | Why not the platform or something already here |
| --- | --- | --- | --- | --- |
| `vitest` | The test runner behind `npm test`, configured in the `test` key of `vite.config.ts`. | The Vitest team | MIT | It reads the build's own config, so tests run through the build's transform. Why it over Jest is not recorded at the time. |
| `@vitest/coverage-v8` | Coverage reporting, `provider: 'v8'`. | The Vitest team | MIT | ADR-015, which weighs it against Istanbul and sets no threshold. |
| `jsdom` | The DOM the tests run in, `environment: 'jsdom'`. | The jsdom organisation | MIT | Node has no DOM. Why it over `happy-dom` is not recorded at the time. |
| `@testing-library/react` | Renders a component and queries it as a user sees it: `render`, `screen`, `within`. | The Testing Library organisation | MIT | The alternative is a root and a `querySelector` in each test. Not recorded at the time as considered. |
| `@testing-library/user-event` | Types and clicks as a user does, in 17 test files. | The Testing Library organisation | MIT | `fireEvent` dispatches one raw event where a user produces several. Not recorded at the time as considered. |
| `@testing-library/jest-dom` | DOM matchers such as `toBeInTheDocument`, loaded by `src/test-setup.ts`. | The Testing Library organisation | MIT | Without it, assertions on the DOM are written by hand against properties. Not recorded at the time as considered. |

The three Testing Library packages peer `@testing-library/dom`, which npm installs and the lockfile
marks as a peer. It is MIT and is not a direct dependency.

### Constraints that already bind these dependencies

- **`react`, `react-dom`, `@types/react` and `@types/react-dom` move together.** `react-dom` peers
  the `react` of its own version, and `@types/react-dom` peers the `@types/react` of its own minor.
  Dependabot opened them as pairs (#241 for `react` and `@types/react`, #243 for `react-dom` and
  `@types/react-dom`), and both failed the frontend gate, so `.github/dependabot.yml` now groups
  the four, as it groups Vitest.
- **`@vitest/coverage-v8` is pinned to Vitest's exact version** by peer dependency (ADR-015), and
  the `vitest` group in `.github/dependabot.yml` moves them together.
- **Vitest sits on Vite, so their majors move together.** `@vitejs/plugin-react` peers `vite` at
  `^8.0.0`, and Vitest peers `vite` at `^6.4.0 || ^7.0.0 || ^8.0.0`.
- **TypeScript is held below 7** until `typescript-eslint` admits it as a peer (ADR-043). Its peer
  range on 2026-10-29 is `>=4.8.4 <6.1.0`.
- **`@eslint/js` follows ESLint's major.** It peers `eslint` at `^10.0.0`.

### The licence of the whole tree

Checked against `frontend/package-lock.json`, which lists 265 packages.

- **The bundle.** Only `react`, `react-dom` and `scheduler` are not marked `dev` in the lockfile,
  and all three are MIT. Every other package is reachable only through a devDependency. The build
  writes the notices of what it bundles to `dist/.vite/license.md`.
- **Weak copyleft and attribution in the dev tree.** `lightningcss` and its eleven platform
  binaries (all optional) are MPL-2.0, a weak copyleft, and come through `vite`. `caniuse-lite` is
  CC-BY-4.0, which asks for attribution, and comes through `browserslist` and `@babel/core` from
  `eslint-plugin-react-hooks`.
- **Other permissive or public-domain licences.** The lockfile also holds Apache-2.0, ISC,
  BSD-2-Clause, BSD-3-Clause, BlueOak-1.0.0, MIT-0 and CC0-1.0.
- **What is absent.** No GPL, AGPL, LGPL, EPL, SSPL or BSL appears, and no package lacks a licence.
- **Provenance.** On 2026-10-29 the latest release of `react`, `react-dom`, `vite`,
  `@vitejs/plugin-react`, `vitest`, `@vitest/coverage-v8`, `jsdom`, `typescript-eslint` and the
  three Testing Library packages carried npm provenance. The latest release of `typescript`,
  `@types/*`, `eslint`, `@eslint/js`, `eslint-plugin-react-hooks` and `prettier` did not.

**Whether these licences are acceptable is open, and this record does not decide it.** `LICENSE`
reserves all rights and HrSched is meant to run as a hosted service, so the owner decides, in
[#280](https://github.com/NaimElijah/HrSched/issues/280). Nothing in CI checks licences: OSV-Scanner
([ADR-047](ADR-047-audit-dependencies-in-ci-with-osv-scanner.md)) audits advisories.

## Consequences

**What this makes easy.** A maintainer can read why a frontend dependency exists, who maintains it
and under what licence in one place, as they can for the backend in `pom.xml`. The constraints that
already bind versions are written down together. The licence of the tree is stated, with the one
question it leaves open, instead of discovered the day somebody asks.

**What this makes hard.**

- **A new frontend dependency needs its own ADR**, and this one cannot be edited to hold it, because
  an accepted ADR is append-only. That is a file, an index row and a number for each `npm install`
  of something new. The cost is the point: it is the check the rule asks for.
- **The record can go stale unseen.** A package removed or replaced leaves its row here. A licence
  or maintainer that changes after 2026-10-29 is recorded nowhere, and no CI step checks either.
- **The reasons are reconstructed.** They describe what each package does today, not what was said
  when it was adopted. Where a package was chosen over another (`@vitejs/plugin-react`, Prettier,
  Vitest, jsdom, the Testing Library packages), the table says the reason was never recorded.

## Revisit triggers

Write a new ADR, superseding this one in part or whole, when:

- a direct dependency is added, replaced or removed. The row of a removed package stays here as
  history, and the new one gets its own record;
- the owner decides #280. If weak copyleft or attribution licences are refused in the dev tree,
  `vite` and `eslint-plugin-react-hooks` each need an answer;
- any package other than `react`, `react-dom` and `scheduler` enters the production bundle, or any
  package in the tree adopts a licence that was absent above. The statement about the bundle and the
  tree would then be false;
- a dependency is archived, deprecated on npm or loses its maintainers;
- ADR-043's trigger fires and TypeScript 7 becomes adoptable.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| A comment beside each dependency, as in `pom.xml` | JSON has no comments. A pseudo-comment key would be a workaround in a file that npm and Dependabot both rewrite. |
| One ADR for each of the eighteen dependencies | Eighteen files and index rows, most of them restating the same four questions. Not rejected for later ones: each new dependency gets its own ADR (Decision). |
| A new section in `frontend/CLAUDE.md` or `architecture.md` | The first loads for agents and is capped at about 60 lines. The second describes the system as it is, not why a choice was made. The rule puts a "why" in an ADR. |
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
