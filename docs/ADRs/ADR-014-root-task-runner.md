# ADR-014: Orchestrate both builds from the root with a Node script

- **Status:** Accepted
- **Date:** 2026-08-30

## Context

[ADR-007](ADR-007-monorepo-backend-and-frontend.md) recorded an explicit debt when it moved the
Maven project into `backend/`:

> The repository baseline's "one command to set up, run, and test" is now unmet at the root. […]
> No root task runner is added here because `make` is not installed on the development machine […]
> **This is a known, accepted debt**, and it gets more expensive the moment `frontend/` lands and
> the root needs to orchestrate two builds — which is the trigger to fix it properly.

`frontend/` has landed ([ADR-013](ADR-013-react-typescript-vite-frontend.md)), so the trigger has
fired. There are now two independent builds, and a contributor arriving at the repository root has
no single command that exercises either of them.

`make`, `just` and `task` are all still absent on the development machine. Node, however, is now
required by the frontend regardless.

## Decision

**A root `package.json` whose scripts delegate to `scripts/run.mjs`.** It adds no tool that the
repository did not already require, and it works identically on Windows and on CI's Linux.

```
npm run setup     install frontend dependencies
npm run dev       backend and Vite together
npm run verify    the whole gate: mvnw verify, then the frontend's own verify
```

**The script orchestrates and nothing else.** `cd backend && ./mvnw verify` remains the entire
backend gate, exactly as ADR-002 requires, and `npm run verify` inside `frontend/` remains the
entire frontend gate. This file must never become a place where build logic accumulates — the
moment a step exists only here, the "CI runs what a developer runs" property ADR-002 protects is
gone.

**CI does not use it.** Each job runs its own project's command in its own working directory, so a
failure names the project that failed. Both jobs run on every push, with **no path filters**:
ADR-007 warned that a wrongly filtered check is skipped silently and still reports green.

## Consequences

**Easier**

- The baseline is met: one command to set up, one to run, one to test, from the root.
- A contributor does not have to know which of two directories a command belongs in.
- Adding a third buildable project is one entry in one map.

**Harder**

- **The root is now nominally an npm project**, which is a slightly odd thing for a repository whose
  primary artefact is a Spring Boot jar. Accepted because the alternative was a shell script plus a
  `.cmd` twin, which is two files that drift apart.
- **Windows needed real care, and getting it wrong was silent rather than loud.** Node refuses to
  spawn a `.cmd` shim without a shell; passing an args array alongside `shell: true` is deprecated
  because arguments are concatenated unescaped; and quoting the command breaks `PATH`/`PATHEXT`
  resolution, so `"npm.cmd"` fails to find its own installation where `npm.cmd` succeeds. All three
  were found by running it. Only paths are quoted now, and every argument in the file is a literal.
- **The Maven wrapper needs PowerShell on Windows.** `mvnw.cmd` is a batch/PowerShell polyglot, so
  `npm run verify` inherits that requirement. It is Maven's, not this script's, and it applies
  equally to `cd backend && mvnw.cmd verify`.
- **One fact now lives in two places again**: the root script and the CI workflow each name the
  per-project commands. That is the smaller version of the drift ADR-002 exists to prevent, and it
  is accepted because making CI call the root script would cost the per-project failure attribution
  that makes a red build readable.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Install `make` and write a Makefile** | The conventional answer, and the one ADR-007 wanted. Rejected because it means every contributor installs a tool solely to run two commands, on a platform where it is not standard — while Node is already mandatory. |
| **A shell script plus a `.cmd` equivalent** | No new ecosystem at the root. Rejected because two files encoding the same steps drift, and the one that drifts is always the one the other platform's developer does not run. |
| **Leave the debt open and document two commands** | Free. Rejected because ADR-007 already decided this, named the trigger, and the trigger has fired — deferring again would make that ADR's commitment meaningless. |
| **Make CI invoke `npm run verify` at the root** | Removes the duplication named above. Rejected because a single job would report one red tick for either project, and the workflow could no longer cache Maven and npm independently. |
| **A Maven aggregator POM driving the frontend via `frontend-maven-plugin`** | One command, and it is the Java-ecosystem answer. Rejected because it makes the backend build depend on Node and destroys the build independence ADR-007 chose deliberately — the backend jar would no longer build without a working npm. |
