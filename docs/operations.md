# Operations

What a person running HrSched needs beyond the quick start: the container image, the application
settings that are not deployment variables, and the meters the application records. HrSched is not
deployed anywhere yet, so this describes what the code does, not a production environment. The
deployment variables are in the [README](../README.md#configuration).

## The image

`backend/Dockerfile` builds the backend into an image
([ADR-061](ADRs/ADR-061-package-the-backend-as-a-container-image-on-a-pinned-temurin-jre.md)). To
build it and check it in one step, with Docker running, from anywhere in a checkout:

```sh
sh backend/smoke-test-image.sh
```

The script builds the image under a tag of its own run and starts it against a throwaway PostgreSQL,
removing both when it ends. It checks
that readiness and health answer `UP`, that `/actuator/info` names the version and the commit, that
the process is not root, holds no capability and can read the certificate bundle, that no program in
the image is setuid or setgid, and that a `/tmp` the solver cannot load from stops it at startup. `READY_TIMEOUT_SECONDS` (default 120) bounds each wait for the
application. CI runs the same script. To build the image alone:

```sh
docker build --build-arg REVISION="$(git rev-parse HEAD)" --tag hrsched-backend backend
```

| What | Value | Why |
| :--- | :--- | :--- |
| Configuration | The variables in [`.env.example`](../.env.example), from the environment | The image holds no credentials and no deployment-specific setting |
| Port | `8080`, for the API and Actuator alike | [ADR-017](ADRs/ADR-017-expose-metrics-to-be-scraped.md) |
| User | UID and GID `10001` | Not root, and not the base image's `ubuntu`, which belongs to the `sudo` group |
| Privileges | None: every capability may be dropped, with `no-new-privileges` | No program in the image is setuid or setgid, and the smoke test runs it with `--cap-drop ALL --security-opt no-new-privileges` |
| Root filesystem | May be read-only | The smoke test runs it read-only |
| `/tmp` | Writable, and allowing code to run from it | OR-Tools extracts its native libraries there and loads them. From a `noexec` mount they do not load, and startup fails ([#378](https://github.com/NaimElijah/HrSched/issues/378)). Docker's plain `--tmpfs /tmp` is `noexec`; `--tmpfs /tmp:exec` is not |
| JVM flags | `-XX:MaxRAMPercentage=50`, `-XX:+ExitOnOutOfMemoryError` | Half the container for the heap, half for the solver's native memory, until [#170](https://github.com/NaimElijah/HrSched/issues/170) measures it. A process out of heap stops, so the platform replaces it. Override them by replacing the entrypoint, not with `JAVA_TOOL_OPTIONS`, which prints a line that is not JSON |
| Commit | `--build-arg REVISION`, required | The `org.opencontainers.image.revision` label, and `git.commit.id` in `/actuator/info` |
| RDS certificate bundle | `/etc/hrsched/rds-global-bundle.pem` | For `sslmode=verify-full` ([#159](https://github.com/NaimElijah/HrSched/issues/159)); nothing uses it yet |

**When the bundle's checksum stops matching**, the build fails at its `ADD`. AWS republishes the
bundle at the same address when it adds or retires a certificate authority. Download it, list what
changed with `openssl storeutl -noout -text -certs global-bundle.pem`, pin the new `sha256sum` in
`backend/Dockerfile`, and say in the commit which authorities were added or removed.

## Settings

Every setting below is set in `backend/src/main/resources/application.properties`.

| Property | Value | Why |
| :--- | :--- | :--- |
| `hrsched.security.token.issuer` / `.audience` | `hrsched` / `hrsched-api` | Required of every token, so one minted elsewhere is not spendable here |
| `hrsched.security.token.lifetime` | `10m` | A stolen access token is useful until it expires, and for the decoder's clock skew past that (the owner confirmed the security defaults, [Q27](requirements/requirements.md#open-questions)) |
| `hrsched.security.session.absolute-lifetime` | `12h` | Fixed when the session starts, so a stolen refresh token cannot extend it |
| `hrsched.security.session.idle-lifetime` | `1h` | Closes a browser somebody walked away from |
| `hrsched.security.password.minimum-length` | `15` | NIST SP 800-63B-4, single-factor |
| `hrsched.security.password.bcrypt-strength` | `12` | Bcrypt's work factor; tests use `4` |
| `hrsched.security.sign-in.lockout-threshold` / `-duration` | `10` / `15m` | When an account locks, and for how long |
| `hrsched.generation.time-limit-per-phase` | `5m` | How long each of the solver's two searches may run before it answers with the best roster found: together, the ten minutes the requirements allow ([ADR-044](ADRs/ADR-044-generate-rosters-with-or-tools-cp-sat.md)) |
| `hrsched.generation.concurrent-runs` | `2` | Generations one instance solves at once; one more is refused with a 503, not queued ([ADR-046](ADRs/ADR-046-generate-in-the-background-and-keep-the-job-in-postgresql.md)) |
| `hrsched.generation.lost-after` | `12m` | How long a generation may stay pending before it reads as lost, its instance having stopped. Startup fails unless it exceeds both searches together |
| `hrsched.web.max-json-document-bytes` | `1048576` | The longest JSON request body, counted in bytes (in characters for a body Jackson detects as UTF-16 or UTF-32, #278). A longer one is refused with a `413` before any use case runs, so one unauthenticated request cannot make the server hold whatever it is sent. `0` or less fails startup, and so does more than `2147483647`, which the form limit below cannot hold. A body declared in any charset but UTF-8 is refused with a `415` before it is read |
| `spring.jackson.factory.constraints.read.max-document-length` | `${hrsched.web.max-json-document-bytes}` | Only hands the value above to Jackson; change the `hrsched` one |
| `spring.jackson.deserialization.accept-float-as-int` | `false` | A number written with a fraction part or an exponent, `2.0` included, sent where a whole number is bound is refused with a `400`, not truncated and stored ([ADR-056](ADRs/ADR-056-refuse-a-fraction-where-a-whole-number-is-bound.md)) |
| `spring.jackson.datatype.enum.fail-on-numbers-for-enums` | `true` | An enum field, such as a role or a preference's strength, takes only a constant's name: a number, or a string holding one, is refused with a `400`, not bound to the constant at that index ([ADR-056](ADRs/ADR-056-refuse-a-fraction-where-a-whole-number-is-bound.md)) |
| `spring.jackson.deserialization.fail-on-unknown-properties` | `true` | A member the request does not take, a misspelt rule or weight included, is refused with a `400`, not dropped and the rest stored as if it were left out ([ADR-058](ADRs/ADR-058-refuse-a-request-member-the-request-type-does-not-have.md)) |
| `spring.servlet.multipart.enabled` | `false` | No route takes a file, so a multipart request is refused with a `415` and never parsed |
| `spring.mvc.formcontent.filter.enabled` | `false` | No route takes form content, so no filter reads a form body ahead of the security chain |
| `server.tomcat.max-http-form-post-size` | `${hrsched.web.max-json-document-bytes}` | A form posted to a route that takes JSON is parsed before it is refused as the wrong type; held to the JSON limit, a larger one is refused with a `413` |
| `management.endpoints.web.exposure.include` | `health,info,metrics,prometheus` | An allow list: `health` and `info` need no token, `metrics` and `prometheus` are refused to everyone, and `env`, `loggers` and `heapdump` stay shut ([ADR-049](ADRs/ADR-049-refuse-the-metrics-to-every-caller-until-a-scraper-exists.md), [#78](https://github.com/NaimElijah/HrSched/issues/78)). `info` names the build's `artifact`, `group`, `name` and `version`, the Maven version the logs carry as `service.version`, from the `build-info` goal in `backend/pom.xml`; it gives no build time, because Maven pins that to 1980 for reproducible jars. In the image it also names the commit, as `git.commit.id`, and nothing else |
| `management.endpoints.web.discovery.enabled` | `false` | `GET /actuator` no longer lists the exposed endpoints, with links built from the request's `Host` header |
| `management.endpoint.health.probes.enabled` | `true` | Liveness and readiness probes outside Kubernetes too |
| `spring.datasource.hikari.data-source-properties.logServerErrorDetail` | `false` | Keeps a refused row ("Failing row contains (...)": a name, hours, an absence) out of the driver's exception text, and so out of the log. It covers the driver only: PostgreSQL's own log still writes the `DETAIL` unless the database sets `log_error_verbosity = terse`, which belongs to the deployment and is not configured here |
| `logging.structured.format.console` | `ecs` | Elastic Common Schema JSON, always ([ADR-016](ADRs/ADR-016-structured-logs-and-a-propagated-trace-id.md)) |
| `logging.structured.ecs.service.name` / `.version` | app name / Maven version | Identify the emitting service on every line |
| `management.tracing.sampling.probability` | `1.0` | Free while nothing is exported |
| `management.{tracing,logging}.export.otlp.enabled`, `management.otlp.metrics.export.enabled` | `false` | No collector exists yet; which backend receives the export is an [open question](requirements/requirements.md#open-questions) |
| `spring.application.name` | `HrSched` | Reported in logs and Actuator |

## Meters

| Meter | What it answers |
| :--- | :--- |
| `http_server_requests_seconds` | Request rate, latency and error rate, per route and status |
| `hrsched_shifts_required_places`, `hrsched_shifts_uncovered_places` | Places generation was asked to fill, and could not fill within the rules: their ratio is coverage, the product's own success metric |
| `hrsched_rosters_refused_total{reason}` | Publications refused as `not_compliant`, `not_verifiable` or `concurrent_publication` |
| `hrsched_access_denied_total{kind}` | Refusals by kind; a rise in `cross_tenant`, or in `sign_in_name_taken` from one account, is a security signal, not a usage one |
| `hrsched_changes_total{context,change}` | Every recorded change, by context and kind |
| `hrsched_generations_busy_total` | Generations refused with a 503 because every run was busy: saturation of the solver ([ADR-046](ADRs/ADR-046-generate-in-the-background-and-keep-the-job-in-postgresql.md)) |
| `jvm_*`, `process_*`, `hikaricp_*` | Saturation: heap, threads, the connection pool |
