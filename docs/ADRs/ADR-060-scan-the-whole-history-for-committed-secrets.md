# ADR-060: Scan the whole history for committed secrets

- **Status:** Accepted
- **Date:** 2026-11-04

## Context

Nothing looks for a committed secret. The ones about to exist are the kind that leak: an RS256
private key in PEM (`HRSCHED_SECURITY_TOKEN_PRIVATEKEY`), database credentials and the bootstrap
operator's password, all named in `.env.example`. The repository is public, so a committed secret
is published, and removing it in a later commit does not unpublish it.

[Issue #150](https://github.com/NaimElijah/HrSched/issues/150) asks for the scan, as part of the
security scan [#250](https://github.com/NaimElijah/HrSched/issues/250). Its premise has moved. It
said GitHub's own secret scanning was unavailable on a private repository. The repository is public
now, so that scanning is available and free, but it is disabled today: `gh api
repos/{owner}/{repo} --jq .security_and_analysis` reports `secret_scanning` and
`secret_scanning_push_protection` as `disabled`.

Forces:

- **A secret in an old commit is as published as one at the tip.** A scan of the tree misses a key
  that a later commit removed.
- **A scan that read nothing must not read as a clean scan.** "No leaks found" and "could not look"
  are different answers, as [ADR-047](ADR-047-audit-dependencies-in-ci-with-osv-scanner.md) found
  for the dependency audit. gitleaks v8.30.1 gives the same answer for both in the cases below.
- **A gate that anyone can silence by editing a comment is not a gate.** The way past a finding has
  to be a reviewed change that says why.
- **A tag can move.** The image is pinned by digest, as ADR-047's is.

## Decision

We scan the whole history of every branch with gitleaks in CI, on every push and with no path
filter, as a fourth job in `.github/workflows/ci.yml`, `secret-scan` ("Secret scan"). It runs
`ghcr.io/gitleaks/gitleaks` v8.30.1, pinned by digest and bumped by hand as ADR-047's image is,
through a plain `docker run --network none` with a read-only mount of the checkout. A finding fails
the job.

**It fails closed.** Each of the following was measured on v8.30.1, not assumed.

- **gitleaks exits 0 having scanned nothing when git refuses the repository.** It logs `0 commits
  scanned` and `no leaks found`. So the job also counts the commits that add at least one line of
  text, with `git log --all --no-merges --numstat`, and fails if the scan read fewer. The bound is
  a lower bound, not an equality, because the scan's own count differs from every count git gives.
  On this history at the time of writing there were 1144 commits in all, 143 of them merges and
  1001 not; the scan read 999, and the bound was 994. A commit the scan counts beyond the bound does
  no harm. A scan that stopped early fails.
- **A shallow clone exits 0 when the key is only in a commit that was cut off.** The job uses
  `fetch-depth: 0` and fails on a shallow clone.
- **A `gitleaks:allow` comment on the line silences a finding by default, with exit 0.** The job
  passes `--ignore-gitleaks-allow`, so a commit cannot silence itself.
- **Without `useDefault = true` the scan runs no rules and reports every commit clean.** On this
  history it read all 999 commits and exited 0, where the default rules find two matches, so the
  commit count does not catch this. The job's first step checks for it. It also fails on a `.gitleaksignore`, which silences a finding with no reason, and checks that
  every allowlist entry has a `description` and `targetRules`.

**Allowlisting.** A false positive is allowed only by a committed `[[allowlists]]` entry in
`.gitleaks.toml`, scoped to one rule, one file and the text of that one false positive, with a
stated reason. Measured: a filler key pasted into an allow-listed file is still found. Two entries
exist. A finding that is a real secret is never allow-listed: it is rotated. The first scan of the
whole history, at the time of writing, found only those two, and neither is a credential.

**Why this tool.** gitleaks is MIT-licensed. From `gh api` on 2026-11-04: the repository is not
archived, its last push was 2026-10-28, its last release 2026-03-21, and it has 524 open issues.
Not verified: its vulnerability history, its OpenSSF Scorecard, and the image's signature or
provenance.

## Consequences

**Easier**

- A secret anywhere in the history of any branch turns CI red.
- Silencing a finding is a reviewed change to `.gitleaks.toml`, with a reason that CI refuses to do
  without. No comment, ignore file or commit message does it.
- No secret, key or account is needed, for the owner or for a fork.

**Harder**

- **This detects; it does not prevent.** A secret is already published by the time CI goes red.
  Preventing it is what push protection would add.
- **A red job blocks nothing yet.** The ruleset has no required status checks
  ([#217](https://github.com/NaimElijah/HrSched/issues/217)), so until they exist the job is a
  signal and the merge button stays enabled.
- **The image digest is bumped by hand.** Nothing proposes the bump, so the scanner stays at v8.30.1
  until someone does it. It is the same cost ADR-047 accepted for its image.
- **A secret that matches no default rule is not found.** The default rules are pattern and entropy
  based. A low-entropy password in a place no rule names passes.
- **The commit-count check is a floor.** It catches a scan that stopped early, not one that read
  every commit and still missed a rule. It also depends on the text of gitleaks's log line.
- **The allowlist checks count lines.** They compare the number of `[[allowlists]]` headers with the
  number of `description` and `targetRules` keys, and do not parse the TOML. They catch an omitted
  field, not one on the wrong entry. Review checks that an entry is scoped to its file and text.
- **A real finding costs a rotation.** Removing the commit does not help once it is pushed.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **GitHub's native secret scanning and push protection alone** | Available and free here, and push protection acts before the push, which this job cannot. Rejected as the only control because it is not a CI gate and is not a reviewed, committed configuration. I did not verify its pattern coverage, so this record does not claim what it covers. It is complementary, and enabling it is a repository setting left to the owner. |
| **`gitleaks/gitleaks-action`** | Its README says a licence key is needed for organisation-owned repositories and not for personal accounts, and its repository's licence reads `NOASSERTION` in the GitHub API. It would work today, and a move to an organisation would break the job. The plain image has no such term. |
| **A `docker://` step** | The runner sets `HOME` for those, so the image's global `safe.directory` setting would go unread. Whether git then refuses the repository on a runner was not tested; a plain `docker run` keeps the setting, and the commit count would catch a refusal either way. |
| **Scanning only the tree** | Misses a secret removed in a later commit, which is still published. |

**Revisit when** any of these happens:

- gitleaks changes its licence;
- 12 months pass with no gitleaks release;
- a missed-secret CVE appears in gitleaks;
- GitHub's native scanning gains generic and PEM detection with a CI gate.
