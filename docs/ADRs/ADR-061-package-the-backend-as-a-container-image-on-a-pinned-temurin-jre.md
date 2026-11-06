# ADR-061: Package the backend as a container image on a digest-pinned Temurin JRE

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

Q5 committed HrSched to running in a container, and the AWS plan runs that container on ECS
Fargate ([#38](https://github.com/NaimElijah/HrSched/issues/38),
[#161](https://github.com/NaimElijah/HrSched/issues/161)). Until now the build produced an
executable jar and nothing turned it into an image
([#157](https://github.com/NaimElijah/HrSched/issues/157)).

Forces:

- **The solver is native code.** OR-Tools' libraries need glibc and the base image's libstdc++,
  and they are extracted into `java.io.tmpdir` and loaded from there (ADR-044). An image without
  glibc cannot run them. Neither can a `/tmp` that refuses to run code: measured, a `noexec` mount
  leaves them unloaded ([#378](https://github.com/NaimElijah/HrSched/issues/378)).
- **The solver's memory is mostly outside the heap.** A run at the largest stated size took the JVM
  to about 7 GB, mostly native memory (`architecture.md`, "At the largest stated size"). A JVM
  sized for a plain web application would give the heap most of a container the solver also
  needs.
- **Time zone rules come from the JDK.** A shift's zone is validated against, and counted in, the
  JVM's own time zone database (ADR-034), so the image's JDK decides which zones exist and which
  daylight-saving rules apply.
- **Logs are ECS JSON on stdout** (ADR-016), so nothing may print a line that is not JSON.
- **CI pins every input by digest or SHA** (ADR-047, ADR-060), because a tag can be moved to other
  content after it was trusted.
- **CI's tests run on Temurin 21** (`actions/setup-java`), on GitHub's Ubuntu 24.04 runners.

## Decision

We build the backend into an image with a multi-stage `backend/Dockerfile`. The build context is
`backend/`, and an allowlist `.dockerignore` admits only `pom.xml`, the wrapper and `src/main/`.

**Build stage.** `eclipse-temurin:21.0.13_9-jdk-noble`, pinned by its index digest. It builds the
jar with the Maven wrapper. It installs `unzip`, because without it the wrapper fetches Maven's
`.tar.gz` and checks it against the `.zip` checksum, which fails. Tests and Spotless are skipped:
the backend job's `./mvnw verify` gates both on every push, and here the tests would lack Docker
and Spotless the git attributes it reads line endings from. The stage is thrown away, so its
unpinned apt package never ships.

**Runtime stage.** `eclipse-temurin:21.0.13_9-jre-noble`, pinned by its index digest:

- Temurin, the vendor CI tests on;
- Ubuntu 24.04, the release CI's runners use;
- glibc and libstdc++, which the solver needs.

On top of it:

- **A user of its own,** UID and GID 10001, set numerically so a platform can tell it is not root.
  It is not the base image's `ubuntu` (UID 1000), which belongs to the `sudo` and `adm` groups.
- **No setuid or setgid program.** The base image ships twelve, `su`, `passwd` and `mount` among
  them; their bits are stripped, so code running as the user has no ready route to root. The image
  needs no Linux capability, and the smoke test runs it with all of them dropped and
  `no-new-privileges`.
- **JVM flags in the entrypoint:** `-XX:MaxRAMPercentage=50`, which leaves half the container to
  the solver's native memory until [#170](https://github.com/NaimElijah/HrSched/issues/170)
  measures it, and `-XX:+ExitOnOutOfMemoryError`, so a process out of heap stops and is replaced.
  They are not in `JAVA_TOOL_OPTIONS`, because the JVM announces that variable on stderr in a line
  that is not JSON.
- **The commit, required as a build argument.** It goes into an OCI label and into
  `/app/git.properties`, which Spring's git contributor reads, so `/actuator/info` names the commit
  beside the version `build-info` gives it. A build without it fails.
- **The RDS certificate bundle.** AWS's global bundle sits at `/etc/hrsched/rds-global-bundle.pem`
  for `sslmode=verify-full` ([#159](https://github.com/NaimElijah/HrSched/issues/159)). It is
  fetched with `ADD --checksum`, so the build fails when AWS republishes it, until someone reads
  what changed and pins the new sum.
- **No `HEALTHCHECK`, and no curl.** The load balancer probes readiness (#161).
- **No `# syntax=` line,** which would pull a Dockerfile frontend image by tag. BuildKit's built-in
  frontend reads `ADD --checksum` and `RUN --mount`.

**What is shipped.**

- All five OR-Tools platforms stay in the jar.
- The image is built for the builder's platform, `linux/amd64` on CI's runners.

**How it is kept current.** Dependabot's `docker` entry proposes base-image bumps against `dev`.
It groups the two stages, and it ignores Java majors, so the image stays on 21 until that is
decided on its own.

**How it is checked.** `backend/smoke-test-image.sh` builds the image and starts it against a
throwaway PostgreSQL. It runs read-only, with `/tmp` mounted executable, and given every
`.env.example` variable. It then checks that:

- readiness and health answer `UP`;
- `/actuator/info` names the version and the commit;
- the process is not root;
- the bundle is readable;
- the same image with a `noexec` `/tmp` stops at startup, naming the `UnsatisfiedLinkError`.

CI's fifth job runs exactly that script on every push.

**Measured on 2026-11-07, on Docker Desktop 29.9.2 (WSL 2):**

- The script passes.
- The image is about 680 MB uncompressed.
- A cold build takes about four minutes, most of it downloading Maven's dependencies. A rebuild of
  changed sources reuses the cache mount and takes under a minute.

**Read, not run:**

- Dependabot updating a `tag@digest` reference: its docker parser handles it, but whether a
  republished tag brings a digest-only bump is unverified until its first pull request.
- The image's attached provenance and SBOM attestations, which were not decoded.

**Not verified:** the image on Fargate, on arm64, or under a memory limit.

## Consequences

**Easier**

- **One image serves every environment.** #161 and #163 run what CI checked, with configuration
  supplied by the environment.
- **The image proves itself.** A broken Dockerfile, a missing system library or an unloadable
  solver turns CI red rather than failing a deploy.
- **Base-image fixes arrive as pull requests,** reviewed and gated like any other change.
- **`/actuator/info` names the version and the commit.**

**Harder**

- **The image is large.** The Ubuntu base, the JRE and a 141 MB jar holding natives for five
  platforms add up.
- **The commit is public.** `/actuator/info` names it to anybody who can reach the port, recorded
  as accepted in the threat model. The edge never routes `/actuator` outward (#153).
- **Fargate has no tmpfs.** A task with a read-only root filesystem needs a volume mounted at
  `/tmp` that allows execution; that is #161's to provide.
- **AWS republishing the RDS bundle turns every build red** until the new checksum is pinned
  (`docs/operations.md`).
- **The heap's share is a guess.** 50 % holds until #170 measures it.
- **The image builds its own jar,** so the jar it ships is not byte-for-byte the one the backend
  job tested, though both come from the same sources and pom.
- **The smoke job is not a required check** ([#217](https://github.com/NaimElijah/HrSched/issues/217)).

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **`gcr.io/distroless/java21-debian12`** | No shell, so a smaller attack surface. Its JVM is Debian's OpenJDK package, not the Temurin CI tests on, and it follows Debian's update cadence. Revisit if the image's vulnerability count becomes a gate, or the public threat model (#171) asks for a shell-less runtime. |
| **Alpine or another musl image** | OR-Tools' natives need glibc (ADR-044). |
| **`amazoncorretto:21`** | Well maintained and glibc-based, but a different vendor from CI, with no offsetting gain. Revisit if an AWS-specific JVM feature is ever needed. |
| **Ubuntu 26.04 (`-resolute`)** | Published the same day with the same Temurin build, but its base was days old, and CI's runners use 24.04, so the image would ship on a libc CI does not test on. Revisit when the runners move to 26.04. |
| **`spring-boot:build-image` (buildpacks)** | Its builder and run images are pulled by tag, and the JVM flags and base are the builder's to choose. |
| **Jib** | A new build plugin, and the image would be defined in the pom with no Dockerfile to read or smoke-test locally. |
| **Copying the jar the backend job built** | It ties the image to CI's workspace, and `docker build` alone would no longer work from a clean clone. |
| **Trimming OR-Tools to the Linux platform(s)** | One jar serves every operating system. Excluding platforms in the repackage would break `java -jar` for developers on Windows and macOS, or need a second, image-only jar. The saving is about 60 MB. Revisit when #161 or #170 measures image pull time as a cost. |
| **Building for arm64 too** | The arm64 natives are already in the jar, so it is a `--platform` flag away. Which architecture Fargate runs is #161's decision. |
| **A scheduled rebuild** | With every base pinned by digest, a rebuild reproduces the same image. JDK and time zone updates arrive as Dependabot's digest bumps. Revisit once images are pushed (#163), if Dependabot's bumps prove too slow. |
| **An extracted or layered jar** | It starts faster and pushes smaller layers, but nothing is pushed yet. Revisit with #163. |
| **Flags in `JAVA_TOOL_OPTIONS`** | The JVM prints a line that is not JSON when it reads the variable (ADR-016). |

**Revisit when** any of these happens:

- Adoptium stops publishing `noble` JRE images, or the Temurin licence changes;
- a critical vulnerability in the base image is not fixed within a week;
- #170 measures the heap or native memory and the 50 % is wrong;
- #161 chooses arm64, or a read-only task cannot mount an executable `/tmp`;
- CI's runners move to Ubuntu 26.04.
