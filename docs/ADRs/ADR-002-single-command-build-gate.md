# ADR-002: Make `./mvnw verify` the single build gate, formatted by Spotless

- **Status:** Accepted
  - **Amended 2026-10-30:** Spotless is no longer 2.44.5: its version is `spotless.version` in
    `backend/pom.xml`. Dependabot covers npm as well as Maven and GitHub Actions
    (`.github/dependabot.yml`).
- **Date:** 2026-08-29

## Context

The project baseline requires one command to set up, run, and test — identical locally and
in CI — and requires CI to run build, test, lint, format check, and a dependency audit on
every push.

Two constraints shape how much of that is achievable today:

- **A README-only command variant drifts.** The moment the pipeline and the documentation
  describe different commands, one of them silently becomes wrong, and the reader is the one
  who finds out.
- **The repository is private.** GitHub's `dependency-review-action` requires Advanced
  Security on private repositories, so the zero-configuration CVE gate is not available.

## Decision

**`./mvnw verify` is the entire gate.** Spotless is bound to Maven's `validate` phase, so
that one invocation performs the format check, compiles, and runs the tests. CI runs that
exact command and nothing else, which makes drift between local and CI structurally
impossible rather than merely discouraged.

Formatting is **Spotless 2.44.5 with google-java-format 1.27.0**. The formatter is
authoritative; `.editorconfig` follows it rather than competing with it.

Dependency hygiene is **Dependabot**: weekly version updates for Maven and GitHub Actions,
with vulnerability alerts enabled on the repository.

## Consequences

**Easier**

- One command to learn, one command to document, one command to run. New contributors
  cannot run "the wrong build".
- Formatting stops being a matter of opinion or review commentary.
- Dependency updates arrive as reviewable pull requests rather than as an annual audit.

**Harder**

- google-java-format is **deliberately not configurable**: 2-space indentation, fixed line
  wrapping, no options. Anyone who dislikes it has no knob to turn, and `.editorconfig` has
  to encode its choices rather than a preference.
- Binding Spotless to `validate` means a formatting slip fails the build **before** the
  tests run, which is occasionally annoying mid-refactor. `./mvnw spotless:apply` fixes it
  in one step; the trade is deliberate, because a format check that runs last is a format
  check people skip.
- Dependabot alerts report vulnerabilities but do not **block** a merge. Until a scanning
  step exists, a known-vulnerable dependency can still reach `main`.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Checkstyle / PMD / SpotBugs** for static analysis | On a codebase of one composition root and one smoke test, a static analyser produces configuration work and no findings. **Revisit when** the first real domain module lands, or the first bug appears that static analysis would have caught. |
| **OWASP `dependency-check-maven`** in CI | Needs an NVD API key to run at usable speed and adds minutes to every build, for a project that is not deployed anywhere. **Revisit before** the first deployment outside a development machine, or if the repository becomes public. |
| **`dependency-review-action`** | Requires GitHub Advanced Security on a private repository. **Revisit if** the repository is made public, at which point it becomes free and should be added. |
| **A separate `lint` CI job** | Would duplicate what `verify` already does and create a second command to keep in sync — the exact drift this ADR exists to prevent. |
| **Formatting in a pre-commit hook instead of the build** | A hook is bypassable with `--no-verify` and is not enforced on CI, so it cannot be the gate. It is a fine future addition *on top of* the build check, not a replacement. |
