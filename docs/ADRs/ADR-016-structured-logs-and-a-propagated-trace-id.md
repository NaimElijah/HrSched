# ADR-016: Emit structured JSON logs everywhere, and propagate a W3C trace id

- **Status:** Accepted
  - **Amended 2026-10-30:** `ActorFromHeaders`, which the text says "must be deleted", has been
    deleted, and the actor now comes from the token
    ([ADR-011](ADR-011-issue-our-own-access-tokens.md)). The trigger on the rejected MDC filter,
    that authentication lands, has fired, and ADR-011 rejected the filter again with a new trigger.
  - **Erratum 2026-10-30:** `trace_id`, under Consequences, should read `traceId`. Micrometer
    Tracing puts the id in the MDC under that name, and the ECS format publishes it as named, as
    both already did in the commit that added this record (c99f71a). `TraceIdHttpTest` reads it
    off the JSON log lines.
- **Date:** 2026-08-30

## Context

[ADR-001](ADR-001-java-spring-boot-maven.md) recorded, as one of the forces choosing the stack:

> The project baseline demands health and readiness endpoints, **structured logging, and
> vendor-neutral instrumentation** from every service.

Only the probes were built. Until this change `backend/src` contained **no logger at all** — not one
`Logger`, `slf4j` import, or `System.out` in any of its classes. The README named the gap plainly
(*"Still owed: structured JSON logging, correlation-ID propagation, and OpenTelemetry
instrumentation"*) and it stayed owed through every branch since.

Two things follow from having no logs, and the second is the one that actually hurts:

- Nothing about a running process is recoverable after the fact.
- **The person who hits a failure has nothing to report.** They see a 403, a 404 or a 422 in a
  browser. Even once logging exists, a support conversation beginning "it broke this morning" cannot
  be joined to a line in a log.

Three constraints shape what could be adopted:

- **Spring Boot 4.1.1 ships structured logging.** `logging.structured.format.console` accepts `ecs`,
  `logstash` or `gelf` with no dependency, no `logback-spring.xml` and no encoder library. Verified
  against the metadata inside `spring-boot-4.1.1.jar`, not recalled.
- **The hexagon forbids the obvious placements.** `HexagonalArchitectureTest` keeps the domain on JDK
  imports and the application layer free of Spring, so a servlet filter can only live in an adapter
  or in `configuration/`, and nothing inward of a port may be annotated by an instrumentation
  library.
- **Q5 is answered: the deployment is containerised.** Logs belong on stdout, collected by whatever
  runs the container, not in a file this process manages.

## Decision

**Logs are ECS JSON on the console, always, with no profile switching it off.** A format that only
production emits is a format nobody reads until the day it matters, and a local console that
disagrees with a production collector is a difference discovered during an incident.

**We add `spring-boot-starter-opentelemetry`.** It supplies the OpenTelemetry API, the Micrometer
Tracing bridge over it, and the OTLP exporters. That gives every request a **W3C trace id**, placed
in the MDC and therefore published by the ECS format on every line that request produces. Lines
emitted outside a request carry none, having no span to belong to. This is
the vendor-neutral instrumentation ADR-001 asked for, rather than a correlation id of our own
invention that joins up with nothing.

**Every request is sampled, and nothing is exported.**
`management.tracing.sampling.probability=1.0`, while the OTLP exporters for traces, logs and metrics
are switched off — `management.tracing.export.otlp.enabled`,
`management.logging.export.otlp.enabled` and `management.otlp.metrics.export.enabled`, all `false`.
Nothing listens on an OTLP endpoint yet; that is Q19. An exporter with nowhere to send retries and
logs its own failure forever, which would drown the logs this decision exists to create.

**`TraceIdResponseFilter` puts the id on the response** as `X-Trace-Id`, and the user interface shows
it on a refusal. The identifier on the screen is the identifier to search the logs for. When no span
is active the header is absent rather than invented: an id naming a trace that appears in no log
line would send whoever quoted it looking for something that was never written.

**No filter puts the organisation, user or role into the MDC.** Those fields reach the logs from the
audit record and from the exceptions themselves — see
[ADR-018](ADR-018-audit-through-a-port-not-a-queryable-trail.md).

## Consequences

**Easier**

- Every line is machine-readable and carries `service.name` and `service.version`, and every line a
  request produces also carries `trace_id`, so a collector needs no parsing rules written for this
  application in particular.
- A user's report is joinable to the log: they quote `X-Trace-Id` and it matches.
- Turning export on later is configuration, not code — one endpoint and one flag per signal.
- Nothing was added to the hexagon. The only new class sits in `configuration/observability`, reads
  no request header, and therefore survives the arrival of authentication (ADR-011) untouched —
  unlike `ActorFromHeaders`, which must be deleted.

**Harder**

- **Local output is now one JSON object per line, and it is not pleasant to read.** That is the price
  of the decision above, taken deliberately. `jq` makes it bearable; a profile would have made the
  difference invisible, which is the problem.
- **`service.environment` is deliberately unset**, so ECS logs carry no environment field until a
  deployment supplies one. A committed default of `local` would have travelled to a real environment
  that forgot to override it and labelled production's logs as a laptop's. An absent field is
  visibly missing; a wrong one is not.
- **The dependency is not small.** One starter brings the OpenTelemetry SDK, its exporters and the
  Micrometer Tracing bridge, most of which does nothing while export is off.
- **Sampling at 1.0 will not survive contact with load.** It costs nothing today because no span
  leaves the process. **Revisit when** export is turned on.
- `X-Trace-Id` is a convention rather than a standard, and the browser learns the name of a header
  the backend chose.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Plain text locally, JSON under a `production` profile** | Much nicer to develop against, and the common arrangement. Rejected because it guarantees the format read every day is not the format shipped, and introduces this repository's first profile in order to hide a difference rather than express one. **Revisit if** reading local output becomes a genuine obstacle rather than an irritation. |
| **`logstash` or `gelf` rather than `ecs`** | All three are one property. ECS chosen because it names `service`, `log.level` and `process.thread` explicitly instead of flattening everything, and is ingested by Elastic, OpenSearch and most collectors without a mapping. Nothing here depends on the choice; it is one word to change. |
| **`logback-spring.xml` plus `logstash-logback-encoder`** | The answer before Boot 3.4. Rejected outright: a dependency and an XML file to maintain for something the framework now does with one property. |
| **A hand-rolled correlation-id filter, and no OpenTelemetry** | Far smaller — a UUID, an MDC entry, a response header, no new dependency. Rejected because ADR-001 committed to *vendor-neutral* instrumentation, and an id of our own invention propagates to nothing: the first collector or downstream call would discard it and the work would be done twice. |
| **Brave and Zipkin instead of OpenTelemetry** | Equally supported by Boot, lighter, and a perfectly good tracer. Rejected because OpenTelemetry is the vendor-neutral one, which is the actual requirement. **Revisit if** the OpenTelemetry SDK's footprint or startup cost is ever measured to matter here. |
| **An MDC filter carrying the organisation, user and role** | Would put the tenant on every line, which is genuinely useful. Rejected because it would duplicate the header knowledge `ActorFromHeaders` centralises and add a second placeholder to delete when ADR-011 lands, while the same fields already reach the log through the audit record and the exceptions. **Revisit when** authentication lands and the acting identity is available ambiently rather than from a header. |
| **W3C `traceresponse` as the response header** | The standards-track name for exactly this. Rejected because it is still a draft, while `X-Trace-Id` is understood everywhere today. **Revisit when** `traceresponse` reaches Recommendation. |
| **Turning OTLP export on, pointed at localhost** | Would make the traces real rather than theoretical. Rejected because nothing listens: the exporter would retry and log a failure every few seconds, drowning the logs this change exists to produce. **Revisit when** Q19 is answered. |
| **A log line per request** | The conventional companion to structured logging. Rejected as duplication: `http.server.requests` already records method, status and latency as a metric ([ADR-017](ADR-017-expose-metrics-to-be-scraped.md)), and a line per request is exactly the hot-path logging this repository's conventions forbid. |
