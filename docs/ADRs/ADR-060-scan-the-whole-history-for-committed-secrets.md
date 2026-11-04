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
- **A false positive needs a way past that says why.** Otherwise the way past is to switch the scan
  off.
- **A tag can move.** The image is pinned by digest, as ADR-047's is.

## Decision

We scan the history of every branch with gitleaks in CI, on every push and with no path filter, as
a fourth job in `.github/workflows/ci.yml`, `secret-scan` ("Secret scan"). It runs
`ghcr.io/gitleaks/gitleaks` v8.30.1, pinned by digest and bumped by hand as ADR-047's image is,
through a plain `docker run --network none` with a read-only mount of the checkout. A finding fails
the job. It is reliable for a PEM private key in the ordinary case, and partial for passwords.

**gitleaks fails open, so its exit code is not trusted alone.** Each of these was measured on
v8.30.1 with filler keys, and each has a guard.

| Measured | What gitleaks answers | The guard |
| --- | --- | --- |
| A config with no rules, `disabledRules`, a spaced global allowlist header, an empty `targetRules`, a renamed `[extend]` | `no leaks found`, exit 0, having read every commit | The scan is shown a filler key on every run, with the same image and config, and must find it. Rule edits that key does not exercise are refused by name |
| git refuses the repository | `0 commits scanned`, `no leaks found`, exit 0, with an `ERR` in the log | An `ERR` in the log fails the job, and so does reading fewer commits than the floor below |
| A key typed into a merge's conflict resolution | `no leaks found`, exit 0: its default `git log` prints nothing for a merge | `--log-opts` adds `-m`, reading merges against each parent, and `T` for a type change |
| A key under a path a later `.gitattributes` line marks `-diff` | `no leaks found`, exit 0: the whole history of that path reads as binary | `* diff` in `.git/info/attributes`, which outranks it, before anything reads the history |
| A shallow clone, with the key in a commit it cut off | Exit 0 | `fetch-depth: 0`, and the job refuses a shallow clone |
| `gitleaks:allow` on the key's line | Silences it, exit 0 | `--ignore-gitleaks-allow`, shown in CI to find the key anyway |

**The floor** counts the commits that add at least one line of text, with `git log --all -m
--numstat`, and the scan must read at least that many. It is a floor rather than an equality because
the scan's own count matches no count git gives: at the time of writing there were 1145 commits, 143
of them merges, the scan read 1143 and the floor was 1138. That catches a scan that stopped well
short. A scan cut within the margin passes, and since the floor reads the same clone it cannot see a
narrowed set of refs.

**A red job says what it found.** `--verbose` names the file, commit and rule, so whoever is rotating
a key need not reproduce the scan first. `--redact` still hides the secret, measured: `Finding:
REDACTED`, `Secret: REDACTED`. The file and commit are public anyway, though a red job does announce
that a leak exists. `--log-level` debug or trace must not be added: the upstream source shows their
events carrying the secret before redaction (read, not run).

**Allowlisting.** A false positive takes a committed entry in `.gitleaks.toml`, scoped to one rule,
one file and the whole text of that one false positive, with a stated reason. Measured: a filler key
pasted into an allow-listed file is still found. Two entries exist, neither hiding a credential: the
PEM header and footer constants in `AccessTokenKey`, and a test value labelled invented in
`AccessTokenPropertiesTest`. A `.gitleaksignore` fails the job.

**A real secret is rotated first.** The scan reads all history and pushed history is not rewritten,
so a rotated secret would keep every run red for good. So after the rotation it takes an entry scoped
by `commits` and path, whose description names the rotation and its issue. The entry records a key
that is dead. It never stands in for rotating one. Nothing says how to rotate the signing key yet, and
that needs an owner before the first real leak.

**Why this tool.** It replaces nothing: no hook, workflow or repository setting scans for secrets,
and the dependency audit looks at dependencies rather than commits. gitleaks is MIT-licensed. From
`gh api` on 2026-11-04: the repository is not archived, its last push was 2026-10-28, its last
release 2026-03-21, and it has 524 open issues. Not verified: its vulnerability history, its OpenSSF
Scorecard, and the image's signature or provenance.

## Consequences

**Easier**

- A PEM private key in any commit of any branch turns CI red, and the job says where.
- The job proves on every run, and after every digest bump, that it can find a key.
- No secret, key or account is needed, for the owner or for a fork.

**Harder**

- **This detects; it does not prevent.** A secret is already published by the time CI goes red.
  Preventing it is what push protection would add, which is
  [#375](https://github.com/NaimElijah/HrSched/issues/375).
- **A red job blocks nothing yet.** The ruleset has no required status checks
  ([#217](https://github.com/NaimElijah/HrSched/issues/217)), so until they exist the job is a
  signal and the merge button stays enabled.
- **The guards catch accidents, not somebody determined.** A pull request's run uses its own
  workflow, so `--exit-code 0` or `--enable-rule` in `ci.yml` switches the scan off, and the ruleset
  requires no approval. A widened path or regex in an allowlist entry, or one with `commits`, is not
  refused either. Review is the only control over these two files, and it is not enforced: a diff to
  either deserves the care a diff to the authentication code gets. Revisit with #217.
- **gitleaks' own default allowlist cannot be subtracted.** It comes with `useDefault`, short of
  copying the rule set. Measured: a filler key with the word `false` in its body is dropped,
  against the unanchored middle of `(?i)^true|false|null$`. The security review estimates about 7e-5
  per 3072-bit key. It also skips paths including `.gitleaks.toml` itself, `.pdf`, `.png` and
  `package-lock.json` (read, not run). Whether to add an independent check is
  [#376](https://github.com/NaimElijah/HrSched/issues/376).
- **Passwords are only partly covered.** On the security review's port of the rules, with invented
  values: a random alphanumeric of 12 or more in an env, YAML or JDBC-query form is found. A random
  password with symbols, one of 11 characters or fewer, a hyphenated passphrase, one of letters only
  and a password in a URL's userinfo are not. The port is not gitleaks: it was not run on the image.
- **What is not read.** A UTF-16 file, commit messages and tag annotations (git behaviour, not run
  through the image). A push that starts no run, such as a tag or a commit carrying one of GitHub's
  skip markers, is not scanned until a later run reaches it.
- **A rule edit can still pass a check that only counts lines.** The allowlist checks compare the
  number of headers with the number of `description`, `targetRules` and `condition` keys, and do not
  parse the TOML. The floor also depends on the text of gitleaks' log line.
- **The digest is bumped by hand.** Nothing proposes the bump, the same cost ADR-047 accepted.
- **Reading merges costs little.** On this history the scan takes seconds, and a merge's text can be
  read twice, once against each parent.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **GitHub's native secret scanning and push protection alone** | Available and free here, and push protection acts before the push, which this job cannot. Rejected as the only control because it is not a CI gate and is not a reviewed, committed configuration. This record does not claim what it covers, as that was not verified. It is complementary, and enabling it is the owner's call: #375. |
| **`gitleaks/gitleaks-action`** | Its README says a licence key is needed for organisation-owned repositories and not for personal accounts, and its repository's licence reads `NOASSERTION` in the GitHub API. It would work today, and a move to an organisation would break the job. The plain image has no such term. |
| **A `docker://` step** | The runner sets `HOME` for those, so the image's global `safe.directory` setting would go unread. Whether git then refuses the repository on a runner was not tested; a plain `docker run` keeps the setting, and the guards would catch a refusal either way. |
| **Scanning only the tree** | Misses a secret removed in a later commit, which is still published. |
| **Parsing `.gitleaks.toml` and validating its structure** | A review proposed about thirty lines of Python doing this. The filler key tests what the config does rather than how it is spelled, in a few lines, and anyone who could get past a parser can edit `ci.yml` anyway. Revisit if a real silencing gets past the filler key and review. |
| **Keeping `--verbose` off the public log** | The security review preferred this. The secret is redacted and the commit is public, and in an incident the location is what is needed first. Revisit if a redaction failure is ever seen. |

**Revisit when** any of these happens:

- gitleaks changes its licence;
- 12 months pass with no gitleaks release;
- a missed-secret CVE appears in gitleaks;
- GitHub's native scanning, once its coverage is known (#375), does what this job does with a CI
  gate.
