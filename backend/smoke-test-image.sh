#!/bin/sh
# Builds the backend image and checks what #157 asks of it, against a throwaway PostgreSQL:
#
#   - readiness answers UP, and so does health, which includes the database check;
#   - /actuator/info names the version and the commit;
#   - the process is not root, and runs on a read-only root filesystem;
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
image="hrsched-backend:smoke"
network="$run"
database="$run-db"
application="$run-app"
without_solver="$run-without-solver"

fail() {
    echo "FAILED: $1" >&2
    exit 1
}

# Removing what may not exist yet is expected to fail, so those failures are discarded. The log is
# shown only when something failed: it is a fresh database's, and holds no personal data.
cleanup() {
    status=$?
    if [ "$status" -ne 0 ] && docker container inspect "$application" >/dev/null 2>&1; then
        echo "--- the application's log ---" >&2
        docker logs "$application" >&2 || true
    fi
    docker rm --force "$application" "$without_solver" "$database" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
    exit "$status"
}
trap cleanup EXIT
# dash runs the EXIT trap on an interrupt only once the interrupt itself is trapped.
trap 'exit 130' INT TERM

# Passes when the text contains the expected fragment. Boot answers compact JSON, and Git Bash has no
# jq, so a fragment match is the portable check.
expect() {
    case "$2" in
        *"$3"*) echo "ok: $1" ;;
        *) fail "$1: expected $3 in $2" ;;
    esac
}

echo "Building $image at $revision"
docker build --build-arg REVISION="$revision" --tag "$image" "$repository/backend"

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

# Starts the image read-only, with the given /tmp mount, against the throwaway database. The port is
# published on loopback only, on whatever port Docker picks, so it cannot clash with anything
# already running.
start_application() {
    docker run --detach --name "$1" --network "$network" \
        --read-only --tmpfs "$2" --publish 127.0.0.1::8080 \
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
readiness=$(curl --silent --fail --retry-all-errors --retry-connrefused \
    --retry "$READY_TIMEOUT_SECONDS" --retry-delay 1 --retry-max-time "$READY_TIMEOUT_SECONDS" \
    "$base/actuator/health/readiness") || fail "readiness did not answer UP within ${READY_TIMEOUT_SECONDS}s"
expect "readiness answers UP" "$readiness" '"status":"UP"'

# Readiness reflects the application's own state, not the database's. Health's overall status
# includes the database check, so it is asked too.
health=$(curl --silent --show-error --fail "$base/actuator/health")
expect "health, which includes the database check, answers UP" "$health" '"status":"UP"'

info=$(curl --silent --show-error --fail "$base/actuator/info")
expect "info names the artifact" "$info" '"artifact":"hrsched"'
printf '%s' "$info" | grep -Eq '"version":"[^"]+"' || fail "info names no version: $info"
echo "ok: info names a version"
expect "info names the commit" "$info" "\"commit\":{\"id\":\"$short_revision\""

uid=$(docker exec "$application" id -u)
[ "$uid" != 0 ] || fail "the process runs as root"
echo "ok: the process runs as uid $uid, not root"

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
docker logs "$without_solver" 2>&1 | grep -q UnsatisfiedLinkError ||
    fail "the application without a loadable solver stopped, but its log names no UnsatisfiedLinkError"
echo "ok: where the solver cannot load, the application stops at startup"

echo "The image at $short_revision passed every check."
