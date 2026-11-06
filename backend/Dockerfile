# The backend as a container image (ADR-061). The build context is backend/:
#
#   docker build --build-arg REVISION="$(git rev-parse HEAD)" --tag hrsched-backend backend
#
# `sh backend/smoke-test-image.sh` builds it the same way and then checks it against a throwaway
# PostgreSQL; CI runs that script on every push.
#
# Each base image is pinned by digest, its tag beside it for people and Dependabot to read. A tag
# can be moved to other content after it was trusted, a digest cannot (ADR-047, ADR-060).
#
# There is no `# syntax=` line on purpose: it would pull a Dockerfile frontend image by tag,
# unpinned. The frontend built into BuildKit already understands ADD --checksum and RUN --mount.

FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d AS build

# The Maven wrapper downloads Maven as a .zip and checks it against distributionSha256Sum. Without
# unzip it fetches the .tar.gz instead and checks that against the same sum, which fails. The
# package is unpinned, but this stage is thrown away: nothing from it ships but the jar.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY . .

# Tests and the format check are skipped here because the backend job's `./mvnw verify` is the gate
# for both, on every push. Here the tests would lack the Docker they need, and Spotless would lack
# the git attributes it reads line endings from. Only the local repository is cached between builds:
# the Maven distribution is downloaded each time, so the wrapper checks it against its checksum.
RUN --mount=type=cache,target=/root/.m2/repository \
    ./mvnw --batch-mode -Dmaven.test.skip=true -Dspotless.check.skip=true package \
    && cp target/*.jar /build/hrsched.jar

# A Temurin JRE, the vendor CI tests on, over Ubuntu 24.04, the release CI's ubuntu-latest runners
# use as this is written. The OR-Tools natives need glibc and libstdc++ (ADR-044), so not Alpine.
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f

# The account the process runs as, numeric so that a platform can check it is not root without
# reading /etc/passwd. It is not the base image's `ubuntu` (UID 1000), which belongs to the sudo and
# adm groups.
ARG APP_UID=10001

# The base image's setuid and setgid programs (su, passwd, mount and others) lose those bits: the
# process needs none of them, and each would be a route to root for code running as the user.
RUN groupadd --gid "$APP_UID" hrsched \
    && useradd --uid "$APP_UID" --gid hrsched --no-create-home --shell /usr/sbin/nologin hrsched \
    && mkdir /app /etc/hrsched \
    && find / -xdev -perm /6000 -type f -exec chmod a-s {} +

# AWS's certificate authorities for RDS, so that the database connection can verify the server it
# reaches (sslmode=verify-full, #159). Pinned by checksum, so when AWS republishes the bundle the
# build fails until somebody reads what changed and pins the new sum (docs/operations.md). ADD
# writes a fetched file owned by root with mode 600, which the process could not read. --chmod
# also applies to a directory ADD creates, leaving it untraversable, so /etc/hrsched is made above.
ADD --checksum=sha256:90a6f1a7978a1b1b096dca6c57511ca48d3b8cf052f8e704baf358f34ea2f21b --chmod=444 \
    https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /etc/hrsched/rds-global-bundle.pem

COPY --from=build /build/hrsched.jar /app/hrsched.jar

# The commit the image is built from, required: without it the image could name its version but not
# its commit. Declared this late because every layer after it is rebuilt for each new commit.
#
# Spring's git info contributor reads git.properties, so /actuator/info names the commit beside the
# version build-info gives it. The file is written here, where the commit is known, rather than into
# the jar, which a local build would have to fill with a placeholder.
ARG REVISION
RUN test -n "$REVISION" || { echo 'Pass --build-arg REVISION="$(git rev-parse HEAD)"' >&2; exit 1; } \
    && printf 'git.commit.id=%s\n' "$REVISION" > /app/git.properties
ENV SPRING_INFO_GIT_LOCATION=file:/app/git.properties

# The version label is emptied because the base image's would otherwise show through, naming
# Ubuntu's release as this image's. The application's own version is in /actuator/info until
# versions are taken from release tags (#149).
LABEL org.opencontainers.image.title="HrSched backend" \
      org.opencontainers.image.source="https://github.com/NaimElijah/HrSched" \
      org.opencontainers.image.revision="$REVISION" \
      org.opencontainers.image.version=""

USER $APP_UID:$APP_UID
EXPOSE 8080

# The flags are here rather than in JAVA_TOOL_OPTIONS, which makes the JVM print a line that is not
# JSON into the log stream (ADR-016).
#   MaxRAMPercentage: the heap's share of the container's memory. Half, because the solver's memory
#     is native and outside the heap (ADR-044); #170 measures it.
#   ExitOnOutOfMemoryError: a process that ran out of heap stops, so the platform replaces it rather
#     than routing requests to it.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/hrsched.jar"]
