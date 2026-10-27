# ADR-047: Audit dependencies for known vulnerabilities in CI with OSV-Scanner

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

The project baseline asks for a dependency vulnerability audit in CI, and a red pipeline blocks the
merge. [ADR-002](ADR-002-single-command-build-gate.md) deferred it. It rejected two tools, each
with a trigger to reopen:

- OWASP `dependency-check-maven`, which needed an NVD API key and added minutes to every build.
  Reopen "before the first deployment outside a development machine, or if the repository becomes
  public".
- GitHub's `dependency-review-action`, which costs money on a private repository. Reopen "if the
  repository is made public".

The repository is public now (see the [README](../../README.md#contributing-and-licence)), so the
second trigger has fired.

Today nothing audits dependencies. Dependabot opens update pull requests and raises alerts, and
neither fails a build, so a known-vulnerable dependency can reach `dev` and `main` unnoticed.
[Issue #37](https://github.com/NaimElijah/HrSched/issues/37) asks for three things: a pull request
that introduces a known high-severity advisory goes red, the severity policy is written down, and
an advisory nobody can fix yet is suppressed only with a reason and an expiry. It is part of the
security scan, [#250](https://github.com/NaimElijah/HrSched/issues/250).

Forces:

- **Most of the backend's tree is not in `pom.xml`.** The Spring Boot parent's BOM supplies the
  versions of Tomcat, Jackson and most of the rest. A tool that reads only the declared
  dependencies is blind to them.
- **GitHub's dependency graph is blind to the same part.** For this repository it lists only the 22
  direct Maven dependencies and plugins, several without a version.
- **A suppression has to expire.** An advisory that is ignored forever is a check turned off.
- **A pull request from a fork has no secrets.** A gate that needs a credential cannot run on it.
- **A scan that errors must not read as a clean scan.** "No vulnerabilities found" and "could not
  look" are different answers.

## Decision

We add a third CI job, `dependency-audit` ("Dependency audit"), to `.github/workflows/ci.yml`. It
calls Google's OSV-Scanner reusable workflow,
`google/osv-scanner-action/.github/workflows/osv-scanner-reusable.yml`, pinned to commit
`b1d267aea5211fa648d9d442fa64b90dbdcbdd36` (v2.7.0), like every other action in the file.

- **Same triggers as the other two jobs:** every push, and pull requests into `main` and `dev`,
  with no path filters.
- **It scans the whole repository.** It runs `--recursive` over `./` with
  `--config=./osv-scanner.toml`, so it reads `backend/pom.xml` and `frontend/package-lock.json`.
  For Maven it resolves the transitive tree through deps.dev, including the versions the Boot
  parent supplies.
- **It fails on any known advisory, whatever its severity.** The severity policy #37 asks for is
  "every severity".
- **The only way past an advisory is an `[[IgnoredVulns]]` entry in `osv-scanner.toml`.** Each entry
  carries `ignoreUntil`, a `reason`, and the issue that tracks removing it. The scanner enforces
  none of the three; review does. The date is the one it does enforce: from v2.7.0, an ignore stops
  applying once `ignoreUntil` has passed, so the advisory fails the build again.
- **`upload-sarif: false`.** Nothing goes to code scanning. The caller still grants `actions: read`,
  `contents: read` and `security-events: write`, because the called job declares those
  permissions and a caller cannot grant it less. `security-events: write` is granted and unused.

**Why this tool.** The tree-resolution claim was tested on a throwaway branch against `dev`.
OSV-Scanner reported `org.apache.tomcat.embed:tomcat-embed-core` 11.0.24 and
`tools.jackson.core:jackson-databind` and `jackson-core` 3.1.5, none of which `pom.xml` names, and
from `frontend/package-lock.json` Vite 8.2.2, undici, zod, brace-expansion and source-map-js.
It needs no secrets, so a fork's pull request can run it. Its reusable workflow fails the job when
the scan itself errors. It is Apache-2.0, maintained by Google, and its v2.7.0 release is dated
2026-10-12. It adds a third-party action, but it replaces no dependency: nothing in the repository
resolves Maven's managed tree against an advisory database.

## Consequences

**Easier**

- A pull request that introduces a known advisory in either project turns red, Boot-managed
  transitive dependencies included.
- An exception is a reviewable line in a file in the repository, with a date after which it stops
  applying.
- No secret, key or account is needed, for the owner or for a fork.

**Harder**

- **An advisory published against something already on `dev` turns every later push red** until it
  is fixed or ignored with an expiry. That is deliberate: the audit fails loudly rather than
  waiting for the next dependency change to look.
- **There is no severity threshold.** A low-severity advisory fails as surely as a critical one, and
  the noise this produces is the main cost. If people answer it by adding ignores without expiries,
  the gate is worth less than none.
- **Test-scope Maven dependencies are not resolved.** Testcontainers, ArchUnit and the like are
  not audited. They do not ship.
- **The commit pin does not fully pin the scanner.** The action references its Docker image by tag,
  so the image can change under an unchanged commit.
- **A red audit blocks nothing yet.** Required status checks are not in place
  ([#217](https://github.com/NaimElijah/HrSched/issues/217)). Until they are, the job reports and
  the merge button stays enabled.
- **The expiry, the reason and the issue reference are conventions.** Nothing fails when an entry
  omits them; only review catches it.

**Relation to ADR-002.** This amends [ADR-002](ADR-002-single-command-build-gate.md), and
supersedes none of its decisions. `./mvnw verify` remains the entire backend gate, and CI still
runs exactly the command a developer runs. The audit is a separate job beside it, because it checks
two projects and a scan is not something a developer runs before each commit. ADR-002's rejections
of `dependency-check-maven` and `dependency-review-action` stand for the reasons it gave; its
consequence that Dependabot "alerts but does not block" is now answered, apart from the required
check in #217. ADR-002's status stays `Accepted`, as ADR-045's does for ADR-023.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **`npm audit`** as the gate | It covers only the frontend, and has no suppression that expires. It stays useful for a developer to run locally. |
| **`actions/dependency-review-action`** v5.0.0 | The dependency graph it reads lists only the 22 direct Maven dependencies and plugins, several without versions, so it is blind to the Boot-managed tree. It also only diffs a pull request, so an advisory published later against what is already on `dev` goes unseen. It does have a working severity threshold, which is why the second revisit trigger below names it. |
| **OWASP `dependency-check-maven`** 13.0.0 | Still slow: NVD rate limits add seconds to each call. Its OSS Index analyser now needs credentials, which a fork's pull request does not have. |
| **Sonatype OSS Index** directly | Credentials are mandatory. |
| **Dependabot alerts alone** | They report and never fail a build, which is the gap this closes. |

**Revisit when** any of these happens:

- noise leads people to add ignores without expiries;
- a reason appears to fail only on high or critical advisories;
- OSV-Scanner misses a Boot-managed transitive dependency that `./mvnw dependency:tree` shows;
- pull requests from forks fail at start-up on the `security-events: write` grant;
- a compliance need arises for a signed SBOM;
- the action's repository is archived or relicensed.
