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

The repository is public now (see the [README](../../README.md#contributing-and-licence)), so both
triggers have fired.

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
- **A suppression has to expire, and has to say why.** An advisory that is ignored forever is a
  check turned off. The scanner honours an ignore without a date or a reason, and a
  `[[PackageOverrides]]` entry can silence a whole package with an optional end date.
- **A pull request from a fork has no secrets.** A gate that needs a credential cannot run on it.
- **A scan that errors must not read as a clean scan.** "No vulnerabilities found" and "could not
  look" are different answers. The first design of this job, which called Google's reusable
  workflow over a recursive walk, failed open: review of the pull request that introduced it traced
  the upstream source and found that a manifest the walk could not resolve was only logged, the
  scan was marked partially successful, the results file was still written, and the workflow's
  guard checked only that the file exists.
- **A tag can move.** An action pinned by commit still referenced its Docker image by tag.

## Decision

We add a third CI job, `dependency-audit` ("Dependency audit"), to `.github/workflows/ci.yml`. It is
a plain job with only `contents: read`, and it runs Google's OSV-Scanner image directly:
`uses: docker://ghcr.io/google/osv-scanner-action@sha256:d54770fe0b09493894e76a02aaed9f51a0513fa57c5186452a755d257b0068a6`
(v2.7.0). That digest is the manifest-list digest of tag v2.7.0, so what runs is what was reviewed.

- **Same triggers as the other two jobs:** every push, and pull requests into `main` and `dev`,
  with no path filters.
- **It names its manifests.** The step passes `--config=./osv-scanner.toml
  --lockfile=./backend/pom.xml --lockfile=./frontend/package-lock.json` instead of walking the tree
  with `--recursive`. A named manifest that cannot be read or resolved is an error, where a walked
  one is only logged. For Maven the scanner resolves the transitive tree through deps.dev,
  including the versions the Boot parent supplies.
- **Every non-zero exit fails the step.** It is a plain step with no `continue-on-error`. The one
  exception is upstream's: the image's entrypoint (`exit_code_redirect.sh` in google/osv-scanner
  v2.7.0) maps exit code 128, "no lockfiles found", to 0. That cannot happen here, because both
  manifests are named.
- **It fails on any known advisory, whatever its severity.** The severity policy #37 asks for is
  "every severity".
- **The only way past an advisory is an `[[IgnoredVulns]]` entry in `osv-scanner.toml`.** Each entry
  carries `ignoreUntil`, a `reason`, and the issue that tracks removing it. The scanner honours
  `ignoreUntil` (it has since v1.9): once the date has passed the advisory fails the build again.
  Nothing in the scanner requires the three fields. The job's first step does: it fails if
  `osv-scanner.toml` holds any `[[PackageOverrides]]`, and fails unless every `[[IgnoredVulns]]`
  has both `ignoreUntil` and `reason`. The issue reference stays a convention that review checks.
- **The image digest is bumped by hand.** Dependabot is not known to update a `docker://…@sha256`
  reference. Advisory data is fetched live from osv.dev, so an older scanner still sees new
  advisories.
- **The backend job gains one step.** It removes `~/.m2/wrapper/dists` before `Verify`.
  `setup-java`'s cache restores the Maven distribution, and the wrapper runs a distribution it
  already has without checking its checksum. Removing it makes every run download Maven and check
  it against `distributionSha256Sum`.

**Why this tool.** The tree-resolution claim was tested on a throwaway branch against `dev`.
OSV-Scanner reported `org.apache.tomcat.embed:tomcat-embed-core` 11.0.24 and
`tools.jackson.core:jackson-databind` and `jackson-core` 3.1.5, none of which `pom.xml` names, and
from `frontend/package-lock.json` Vite 8.2.2, undici, zod, brace-expansion and source-map-js
(run [40452899927](https://github.com/NaimElijah/HrSched/actions/runs/40452899927), with this job as
it now is). The fail-closed claim was tested the same way: with the Boot parent made unresolvable
and npm clean, the scan found no advisory but exited 127, and the job was red (run
[40453284329](https://github.com/NaimElijah/HrSched/actions/runs/40453284329)); the reusable
workflow this job replaced would have reported that scan as clean, as the review of #268 found.
It needs no secrets, so a fork's pull request can run it. It is Apache-2.0, maintained by Google,
and its v2.7.0 release is dated 2026-10-12. It adds a third-party container image, but it replaces
no dependency: nothing in the repository resolves Maven's managed tree against an advisory
database.

## Consequences

**Easier**

- A pull request that introduces a known advisory in either project turns red, Boot-managed
  transitive dependencies included.
- A scan that could not resolve a manifest turns red too, rather than reading as clean.
- An exception is a reviewable line in a file in the repository, with a date after which it stops
  applying and a reason that CI refuses to do without.
- No secret, key or account is needed, for the owner or for a fork.

**Harder**

- **An advisory published against something already on `dev` turns every later push red** until it
  is fixed or ignored with an expiry. That is deliberate: the audit fails loudly rather than
  waiting for the next dependency change to look.
- **There is no severity threshold.** A low-severity advisory fails as surely as a critical one, and
  the noise this produces is the main cost. If people answer it by adding ignores with a distant
  date and a thin reason, the gate is worth less than none.
- **Maven build plugins are not audited.** The `spring-boot-maven-plugin`, Spotless, JaCoCo and
  their dependencies run in the build and do not appear in the resolved tree. Test-scope
  dependencies of the root project, such as Testcontainers and ArchUnit, are audited.
- **A partly resolved Maven graph is still not detected.** Naming the manifests turns an
  unresolvable one into an error, but if deps.dev resolves some nodes and fails others, upstream
  does not report it.
- **The wrapper checksum covers the Maven distribution only.** Plugins and dependencies are still
  trusted from Maven Central by version.
- **The image digest is bumped by hand.** A scanner that falls behind still sees new advisories,
  because advisory data is live, but the scanner's own code stays at v2.7.0 until someone
  bumps it.
- **The ignore check counts lines.** It compares the number of `[[IgnoredVulns]]` headers with the
  number of `ignoreUntil` and `reason` keys, and does not parse the TOML. It catches an omitted
  field, not a field on the wrong entry.
- **A red audit blocks nothing yet.** Required status checks are not in place
  ([#217](https://github.com/NaimElijah/HrSched/issues/217)). Until they are, the job reports and
  the merge button stays enabled.

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
| **Google's reusable workflow**, pinned by commit, over `--recursive` | It failed open on a partial scan, as the forces above describe. It also declares `security-events: write` and `actions: read`, which a caller cannot withhold, and it names its image by tag. The image run directly, with named manifests, closes all three. |
| **`npm audit`** as the gate | It covers only the frontend, and has no suppression that expires. It stays useful for a developer to run locally. |
| **`actions/dependency-review-action`** v5.0.0 | The dependency graph it reads lists only the 22 direct Maven dependencies and plugins, several without versions, so it is blind to the Boot-managed tree. It also only diffs a pull request, so an advisory published later against what is already on `dev` goes unseen. It does have a working severity threshold, which is why the second revisit trigger below names it. |
| **OWASP `dependency-check-maven`** 13.0.0 | Still slow: NVD rate limits add seconds to each call. Its OSS Index analyser now needs credentials, which a fork's pull request does not have. |
| **Sonatype OSS Index** directly | Credentials are mandatory. |
| **Dependabot alerts alone** | They report and never fail a build, which is the gap this closes. |

**Revisit when** any of these happens:

- noise leads people to add ignores without expiries, or with expiries that are never reconsidered;
- a reason appears to fail only on high or critical advisories;
- OSV-Scanner misses a Boot-managed transitive dependency that `./mvnw dependency:tree` shows;
- Dependabot cannot keep the image digest current and it falls far behind;
- upstream publishes digest-pinned action images;
- a compliance need arises for a signed SBOM;
- the scanner's repository is archived or relicensed.
