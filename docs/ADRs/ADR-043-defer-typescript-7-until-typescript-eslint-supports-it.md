# ADR-043: Stay on TypeScript 6 until typescript-eslint supports TypeScript 7

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

The frontend gate is `npm run verify` ([ADR-014](ADR-014-root-task-runner.md)), and its lint step
runs ESLint with `typescript-eslint`'s recommended config (`frontend/eslint.config.js`). The
frontend pins TypeScript 6.0.3.

Dependabot opened PR #13 on 2026-08-30 to bump TypeScript to 7.0.2. It never passed CI: `npm ci`
stops with `ERESOLVE`, because `typescript-eslint` declares the peer `typescript >=4.8.4 <6.1.0`.
That range has not moved in any release since. On 2026-10-20 the latest release is 8.72.0, and it
still declares `<6.1.0`. So TypeScript 7 can't be installed alongside the linter the gate runs.

Because #13 can never go green, it sat open for seven weeks. It also held one of the five slots
`open-pull-requests-limit` gives the npm ecosystem.

## Decision

We stay on TypeScript 6. `.github/dependabot.yml` ignores TypeScript major updates
(`version-update:semver-major`), so minor and patch releases within 6 still arrive as pull
requests, and no TypeScript 7 pull request is opened.

## Consequences

**What this makes easy.** The gate stays green. No permanently red pull request sits open teaching
people to ignore red, and the npm slot it held is free. The ignore is a reviewable line in the
repository, and the comment beside it points here.

**What this makes hard.** Nothing tells anyone when TypeScript 7 becomes adoptable. Dependabot
stays silent for as long as the ignore is in place, so the revisit trigger below has to be checked
by a person. The ignore also covers every later major, not only 7.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| Force the install with `--legacy-peer-deps` or an npm `overrides` entry | The linter would run on a compiler version its maintainers haven't declared support for. Whatever it then reports, or misses, can't be trusted, and the gate only means something if its checks can be. |
| Drop `typescript-eslint` | It would take TypeScript linting out of the gate to adopt a compiler version nothing here needs yet. |
| Leave #13 open until upstream catches up | A pull request that can't pass CI is noise, and it holds one of the npm ecosystem's five pull-request slots. |
| Close #13 with `@dependabot ignore this major version` | The ignore would live in Dependabot's own state, out of sight of anyone reading the repository. The configuration file is reviewed and carries a pointer to this record. |

**Revisit when** a `typescript-eslint` release declares a `typescript` peer range that admits 7.x.
To check, run `npm view typescript-eslint@latest peerDependencies`. When it does, remove the
`ignore` entry from `.github/dependabot.yml` and take the major bump as its own pull request.
