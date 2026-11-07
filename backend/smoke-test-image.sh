#!/bin/sh
# Builds the backend image and checks what #157 asks of it, against a throwaway PostgreSQL:
#
#   - readiness answers UP, and so does health, which includes the database check;
#   - /actuator/info names the version and the commit;
#   - the process is not root, and runs on a read-only root filesystem with every capability
#     dropped and no way to gain privileges; no program in the image is setuid or setgid;
#   - the RDS certificate bundle is readable by it;
#   - where the solver's native libraries cannot load, the application stops at startup rather
#     than reporting ready without a solver (#378).
#
# CI's "Backend image" job runs exactly this, and anybody with Docker can run it from anywhere in a
# checkout:
#
#   sh backend/smoke-test-image.sh
#
# It never touches the local development database. The throwaway one has its own network and
# publishes no port, and every container is removed on exit, whether the checks pass or fail.
set -eu

# Git Bash rewrites an argument that looks like a POSIX path, such as /tmp:exec, into a Windows one,
# which Docker then refuses as a mount path. Elsewhere the variable means nothing.
export MSYS_NO_PATHCONV=1

# How long the application may take to report ready, in seconds. Most of it goes on starting the
# JVM and migrating an empty database on a cold runner. Overridable for a slow machine.
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-120}"
# PostgreSQL accepts connections within seconds of starting; this only bounds a start that hangs.
DATABASE_TIMEOUT_SECONDS=60
# How long one HTTP request may take. Every answer checked here is immediate once the application is
# up; the bound stops a connection that is accepted but never answered from hanging the run.
REQUEST_TIMEOUT_SECONDS=10
# The key size .env.example suggests for a deployment's signing key.
SIGNING_KEY_BITS=3072
# What the image reports as the commit: Spring shortens git.commit.id to this many characters.
SHORT_COMMIT_LENGTH=7
# Where the Dockerfile puts the RDS bundle.
RDS_BUNDLE=/etc/hrsched/rds-global-bundle.pem

repository=$(git rev-parse --show-toplevel)
revision=$(git rev-parse HEAD)
short_revision=$(printf '%s' "$revision" | cut -c "1-$SHORT_COMMIT_LENGTH")
run="hrsched-smoke-$$"
image="hrsched-backend:$run"
network="$run"
database="$run-db"
application="$run-app"
without_solver="$run-without-solver"

fail() {
    echo "FAILED: $1" >&2
    exit 1
}

# Removing what may not exist yet is expected to fail, so those failures are discarded. When a check
# failed, every container's log is shown before it goes: they are a fresh database's and the
# applications started against it, and hold no personal data. The image is removed too; the build
# cache that makes the next build quick is kept.
cleanup() {
    status=$?
    if [ "$status" -ne 0 ]; then
        for container in "$application" "$without_solver" "$database"; do
            if docker container inspect "$container" >/dev/null 2>&1; then
                echo "--- the log of $container ---" >&2
                docker logs "$container" >&2 || true
            fi
        done
    fi
    docker rm --force "$application" "$without_solver" "$database" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
    docker image rm "$image" >/dev/null 2>&1 || true
    exit "$status"
}
trap cleanup EXIT
# dash runs the EXIT trap on a signal only once the signal itself is trapped. Each exits with the
# status a shell reports for that signal.
trap 'exit 130' INT
trap 'exit 143' TERM

# Passes when the text contains the expected fragment. Boot answers compact JSON, and Git Bash has no
# jq, so a fragment match is the portable check.
expect() {
    case "$2" in
        *"$3"*) echo "ok: $1" ;;
        *) fail "$1: expected $3 in $2" ;;
    esac
}

echo "Building $image at $revision"
# --load, because a buildx builder other than Docker's default keeps the result in its cache, where
# docker run cannot find it.
docker build --load --build-arg REVISION="$revision" --tag "$image" "$repository/backend"

# The image compose.yaml names, read through compose itself, so the tag stays where the tests'
# PostgresDatabase already expects it and nowhere else (#62).
postgres_image=$(docker compose --file "$repository/backend/compose.yaml" config --images postgres)

# Every credential is generated for this run and passed by name, so none appears in a command line.
# The key is read from openssl's output rather than a file: with path conversion off, a native
# Windows openssl and Git Bash would not agree on where a temporary file is.
POSTGRES_PASSWORD=$(openssl rand -hex 16)
SPRING_DATASOURCE_URL="jdbc:postgresql://$database:5432/hrsched"
SPRING_DATASOURCE_USERNAME=hrsched
SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
HRSCHED_SECURITY_TOKEN_PRIVATEKEY=$(openssl genpkey -quiet -algorithm RSA -pkeyopt "rsa_keygen_bits:$SIGNING_KEY_BITS")
[ -n "$HRSCHED_SECURITY_TOKEN_PRIVATEKEY" ] || fail "openssl produced no signing key"
HRSCHED_BOOTSTRAP_OPERATOR_SIGNINNAME=operator
HRSCHED_BOOTSTRAP_OPERATOR_PASSWORD=$(openssl rand -hex 16)
export POSTGRES_PASSWORD SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD \
    HRSCHED_SECURITY_TOKEN_PRIVATEKEY HRSCHED_BOOTSTRAP_OPERATOR_SIGNINNAME \
    HRSCHED_BOOTSTRAP_OPERATOR_PASSWORD

docker network create "$network" >/dev/null
docker run --detach --name "$database" --network "$network" \
    --env POSTGRES_DB=hrsched --env POSTGRES_USER=hrsched --env POSTGRES_PASSWORD \
    "$postgres_image" >/dev/null

# Over TCP, not the socket: while the image initialises the database its server listens on the
# socket alone, and the application, connecting over TCP, would fail fast against it. pg_isready
# answers at once rather than waiting, hence the loop.
deadline=$(($(date +%s) + DATABASE_TIMEOUT_SECONDS))
until docker exec "$database" pg_isready --quiet --host 127.0.0.1 --username hrsched --dbname hrsched; do
    [ "$(date +%s)" -lt "$deadline" ] || fail "PostgreSQL accepted no connection within ${DATABASE_TIMEOUT_SECONDS}s"
    sleep 1
done

# Starts the image read-only, with the given /tmp mount, against the throwaway database, holding no
# capability and unable to gain one: what the application needs, and no more. The port is published
# on loopback only, on whatever port Docker picks, so it cannot clash with anything already running.
start_application() {
    docker run --detach --name "$1" --network "$network" \
        --read-only --tmpfs "$2" --cap-drop ALL --security-opt no-new-privileges \
        --publish 127.0.0.1::8080 \
        --env SPRING_DATASOURCE_URL --env SPRING_DATASOURCE_USERNAME --env SPRING_DATASOURCE_PASSWORD \
        --env HRSCHED_SECURITY_TOKEN_PRIVATEKEY \
        --env HRSCHED_BOOTSTRAP_OPERATOR_SIGNINNAME --env HRSCHED_BOOTSTRAP_OPERATOR_PASSWORD \
        "$image" >/dev/null
}

# OR-Tools extracts its native libraries into java.io.tmpdir and loads them from there, so /tmp is
# mounted executable; Docker mounts a plain --tmpfs noexec (ADR-044).
start_application "$application" /tmp:exec
port=$(docker port "$application" 8080/tcp | head -n 1 | sed 's/.*://')
base="http://127.0.0.1:$port"

# Readiness answers 503 until the application is ready, and the connection is refused or dropped
# before it listens, so every error is retried until the deadline. Each retry's error goes
# unprinted; the one that matters is the failure below.
readiness=$(curl --silent --fail --max-time "$REQUEST_TIMEOUT_SECONDS" --retry-all-errors \
    --retry-connrefused --retry "$READY_TIMEOUT_SECONDS" --retry-delay 1 \
    --retry-max-time "$READY_TIMEOUT_SECONDS" \
    "$base/actuator/health/readiness") || fail "readiness did not answer UP within ${READY_TIMEOUT_SECONDS}s"
expect "readiness answers UP" "$readiness" '"status":"UP"'

# Readiness reflects the application's own state, not the database's. Health's overall status
# includes the database check, so it is asked too.
health=$(curl --silent --show-error --fail --max-time "$REQUEST_TIMEOUT_SECONDS" "$base/actuator/health")
expect "health, which includes the database check, answers UP" "$health" '"status":"UP"'

info=$(curl --silent --show-error --fail --max-time "$REQUEST_TIMEOUT_SECONDS" "$base/actuator/info")
expect "info names the artifact" "$info" '"artifact":"hrsched"'
printf '%s' "$info" | grep -Eq '"version":"[^"]+"' || fail "info names no version: $info"
echo "ok: info names a version"
expect "info names the commit" "$info" "\"commit\":{\"id\":\"$short_revision\""

# Anybody may read /actuator/info, so it must name the build and the commit and nothing more: a
# setting that adds the environment or the process to it would otherwise pass every check above.
# The outer braces are dropped and every nested object, innermost first, collapsed to an O,
# leaving only the top-level keys.
top_level=$(printf '%s' "$info" | sed -e 's/^{//' -e 's/}$//' -e ':nested' -e 's/{[^{}]*}/O/' -e 't nested')
case "$top_level" in
    '"build":O,"git":O' | '"git":O,"build":O') echo "ok: info names nothing but the build and the commit" ;;
    *) fail "info names more than the build and the commit: $info" ;;
esac

# The process that serves requests, not a process started beside it.
uid=$(docker exec "$application" stat -c %u /proc/1)
[ "$uid" != 0 ] || fail "the process runs as root"
echo "ok: the process runs as uid $uid, not root"

# The capability drop, no-new-privileges and the read-only root are the deployment's to apply
# (#161). These checks prove the image runs under them, read from the kernel's view of the serving
# process rather than trusted from the flags above. The bounding set is read, not the effective set,
# which is empty for any process that is not root whatever was dropped. The write is tried as root,
# so that only a read-only filesystem, not a file's permissions, can refuse it.
process_status=$(docker exec "$application" cat /proc/1/status)
expect "the process can hold no capability" "$process_status" "$(printf 'CapBnd:\t0000000000000000')"
expect "the process cannot gain privileges" "$process_status" "$(printf 'NoNewPrivs:\t1')"
refused=$(docker exec --user 0 "$application" sh -c ': > /app/written' 2>&1) &&
    fail "the root filesystem accepted a write"
expect "the root filesystem refuses a write, even root's" "$refused" 'Read-only file system'

# The flags in the entrypoint, read from the running JVM's command line, so an entrypoint that lost
# them, or passed them where the JVM ignores them, fails here.
command_line=$(docker exec "$application" cat /proc/1/cmdline | tr '\000' ' ')
expect "the JVM holds the heap to its share of the container" "$command_line" '-XX:MaxRAMPercentage=50'
expect "the JVM exits when it runs out of heap" "$command_line" '-XX:+ExitOnOutOfMemoryError'

# Searched as root, so no directory the application cannot read hides a program from the search.
privileged=$(docker run --rm --user 0 --entrypoint find "$image" / -xdev -perm /6000 -type f)
[ -z "$privileged" ] || fail "programs in the image are setuid or setgid: $privileged"
echo "ok: no program in the image is setuid or setgid"

docker exec "$application" test -r "$RDS_BUNDLE" || fail "$RDS_BUNDLE is not readable by the process"
echo "ok: the RDS certificate bundle is readable"

# The same image with a noexec /tmp, from which the solver's libraries cannot load. OR-Tools'
# loader swallows that failure on Linux, so only the application's own check stops it starting
# ready without a solver (#378). The log must say why it stopped, so a failure for another reason
# does not pass for this one.
start_application "$without_solver" /tmp
deadline=$(($(date +%s) + READY_TIMEOUT_SECONDS))
while [ "$(docker container inspect --format '{{.State.Running}}' "$without_solver")" = true ]; do
    [ "$(date +%s)" -lt "$deadline" ] ||
        fail "the application was still running ${READY_TIMEOUT_SECONDS}s after starting without a loadable solver"
    sleep 1
done
stopped=$(docker logs "$without_solver" 2>&1)
expect "the application without a loadable solver names the generator as the cause" "$stopped" 'cpSatRosterGenerator'
expect "and the UnsatisfiedLinkError as the reason" "$stopped" 'UnsatisfiedLinkError'
echo "ok: where the solver cannot load, the application stops at startup"

echo "The image at $short_revision passed every check."
