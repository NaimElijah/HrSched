# ADR-017: Expose metrics for a scraper to pull, and ship no monitoring stack

- **Status:** Accepted
  - **Amended 2026-10-30:**
    [ADR-049](ADR-049-refuse-the-metrics-to-every-caller-until-a-scraper-exists.md) refuses the
    `metrics` and `prometheus` endpoints to every caller until a scraper exists. Everything else
    here stands. The count of "four meters of our own" has moved; the `hrsched.` meters the code
    registers are the current set.
- **Date:** 2026-08-30

## Context

Actuator has been on the classpath since [ADR-001](ADR-001-java-spring-boot-maven.md), exposing
`health` and `info` and nothing else. Micrometer was collecting JVM, process and HTTP meters the
whole time; there was simply no way to read them, and no record of that having been decided.

[ADR-016](ADR-016-structured-logs-and-a-propagated-trace-id.md) added logs and a trace id, which
answer *what happened to this one request*. They answer nothing about rates, latency or saturation,
and they are the wrong tool for it: counting log lines to find an error rate is how you discover
your log volume is the metric.

Two forces bound what could be built:

- **Q5 is answered only in shape.** The deployment is containerised; the provider is deliberately
  deferred. So there is no environment whose monitoring stack this repository could describe, and
  anything committed as configuration for one would be a guess that no test or CI job could check.
- **There is no authentication anywhere.** Whatever `/actuator` exposes is exposed to everyone who
  can reach the port, exactly like `/api`.

## Decision

**We add `io.micrometer:micrometer-registry-prometheus` and expose
`health,info,metrics,prometheus`.** Metrics are **pulled** from `/actuator/prometheus`. Nothing is
pushed: the OTLP metrics exporter stays off with the rest (ADR-016), and the Prometheus pushgateway
stays off by its own default.

**The exposure list is an allow list, and `loggers`, `env` and `heapdump` are deliberately not on
it.** With no authentication, `loggers` would let any caller change log levels at runtime and `env`
and `heapdump` would hand out configuration and memory. `MetricsHttpTest` asserts each of them
answers 404, so opening one is a build failure rather than a review comment.

**Actuator stays on port 8080 with the API.** A separate management port is the usual mitigation,
but while `/api` itself accepts any claimed identity from a header, isolating `/actuator` alone
would be theatre — and it would fork the Spring test context to reach it. **Revisit when**
authentication lands (ADR-011) or when this is first deployed, whichever comes first.

**The product's own success metric is recorded in the generator adapter.**
`hrsched.shifts.required` and `hrsched.shifts.uncovered` record, per generation, the places asked
for and the places that could not be filled without breaking a rule. `GreedyRosterGenerator` is the
only place that knows both: by the time a roster reaches the service, an unfilled place and a place
nobody needed look identical. Recording it there is also what keeps Micrometer out of the hexagon —
the application layer may not import it, and does not need to.

**No monitoring stack is committed.** No `docker-compose`, no Prometheus scrape config, no Grafana
dashboards, no alert rules. This repository exposes the endpoint and documents what is on it.

## Consequences

**Easier**

- Rate, latency, error rate and saturation are all readable now: `http.server.requests` gives
  per-endpoint latency and status, and the JVM and process meters give the rest, for no code at all.
- Coverage — the thing the product exists to produce — is measurable, and measurable as a ratio,
  because the denominator is published alongside the gap.
- Anything that speaks Prometheus can scrape it, including an OpenTelemetry Collector, so this does
  not foreclose the OTLP path in ADR-016.
- The endpoints that must stay shut are named in a test rather than trusted to review.

**Harder**

- **`/actuator/metrics` and `/actuator/prometheus` are unauthenticated**, and they disclose route
  names, request volumes and JVM internals to anyone who can reach the port. That is a real
  exposure, accepted only because the API beside it is no better guarded, and recorded here so it
  cannot be forgotten when it stops being true.
- **`MetricsHttpTest` needs `@AutoConfigureMetrics` and therefore a second Spring context.** Boot
  switches metrics exporters off under test, so `/actuator/prometheus` answers 404 in a test while
  working perfectly in production; the annotation opts back in, and its presence changes the context
  cache key. This is the first `@SpringBootTest` in the repository that cannot share the one context
  the others use. It was found by running the test and watching it 404, then reading the condition
  evaluation report — not by reasoning about it.
- **A metric nobody scrapes changes nothing.** Until Q19 names a backend, this is an endpoint with
  no consumer. The same honest limitation ADR-015 accepted for coverage.
- Two more meters to keep meaningful. A distribution summary recorded on every generation is
  cheap, but it is now part of the generator's contract.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Commit a `docker-compose` Prometheus and Grafana stack, with dashboards and alert rules as code** | The most useful-looking option, and the one that produces something you can actually open. Rejected because Q5 has not named a provider, so the stack would describe an environment that does not exist; and because nothing in `./mvnw verify` or CI can check a dashboard JSON or an alert rule, so it would rot silently — the exact failure mode `ci.yml` warns about for path filters. **Revisit when** Q5's provider is chosen, or the first time an incident is diagnosed by reading logs because no dashboard existed. |
| **Push metrics over OTLP instead of exposing a scrape endpoint** | Consistent with the OpenTelemetry choice in ADR-016, and the exporter is already on the classpath. Rejected because pushing needs a destination and there is none (Q19); a scrape endpoint needs only a scraper, and an OpenTelemetry Collector can scrape it. **Revisit when** Q19 is answered — at which point pull and push are one property apart, not a rewrite. |
| **Put Actuator on its own management port** | The standard mitigation for the exposure named above, and one property. Rejected as theatre while the API on 8080 believes any identity claimed in a header, and because reaching a second port from `@SpringBootTest` means `RANDOM_PORT` and another Spring context. **Revisit when** ADR-011 lands or this is deployed. |
| **Expose `loggers` so log levels can be changed at runtime** | Genuinely useful during an incident. Rejected outright while there is no authentication: it is a write endpoint that changes the behaviour of the running system. **Revisit when** ADR-011 lands. |
| **Record the domain metrics in `SchedulingService`** | The obvious place, and where the roster actually is. Rejected because the application layer may not import Micrometer — `HexagonalArchitectureTest` forbids it — and because the service no longer knows how many places went unfilled. |
| **A dedicated timer for roster generation** | A baseline to judge a future solver against, which ADR-005 will eventually want. Rejected as duplication: `http.server.requests{uri="/api/rosters"}` already times the request that generation dominates. **Revisit when** generation stops being the dominant cost of that request, or moves off the request thread. |
| **A `MeterFilter` enforcing a naming or cardinality policy** | Prevents a future tag from exploding cardinality. Rejected as anticipation: there are four meters of our own and none is tagged by anything unbounded. **Revisit when** a meter is first tagged by an organisation, employee or roster id — which is precisely the change that would need it. |
