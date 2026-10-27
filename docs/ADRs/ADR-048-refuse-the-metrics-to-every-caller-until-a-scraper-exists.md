# ADR-048: Refuse the metrics endpoints to every caller until a scraper exists

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

[ADR-017](ADR-017-expose-metrics-to-be-scraped.md) exposes `health,info,metrics,prometheus` and
[ADR-032](ADR-032-close-the-security-chain-by-default.md) permits `/actuator/**` to anybody.
ADR-017 accepted that `/actuator/metrics` and `/actuator/prometheus` were unauthenticated "only
because the API beside it is no better guarded", and set its own trigger: revisit when
[ADR-011](ADR-011-issue-our-own-access-tokens.md) lands. It has landed. The API now refuses every
caller without a token, and the metrics are the one family of routes that still answers anybody.

The full-system security scan (#250) found that this is more than a description of the system:

- **The metrics say whether a sign-in name is a real account.** Sign-in deliberately records nothing
  for an unknown name, so that its refusal is identical whether or not the name exists.
  `hrsched.changes{context="access",change="ACCOUNT_LOCKED"}` counts up only when a real, active
  account locks. A caller who fails to sign in as a name until the lockout threshold, and reads the
  counter before and after, learns the answer sign-in is careful never to give.
- **The metrics show a prober their own detection signal.** The same open endpoint publishes the
  `hrsched.access.denied{kind="cross_tenant"}` series, the signal that a cross-tenant attempt was
  noticed ([ADR-018](ADR-018-audit-through-a-port-not-a-queryable-trail.md)). A prober who can read
  it can see whether they have been seen.
- **Nothing scrapes the metrics.** Q19 and [#165](https://github.com/NaimElijah/HrSched/issues/165)
  decide the backend, so there is no consumer whose access a refusal would break.
- **Who may reach the port is not decided.** [#78](https://github.com/NaimElijah/HrSched/issues/78)
  is the open question of whether Actuator can be reached from outside, and
  [#153](https://github.com/NaimElijah/HrSched/issues/153) is where the topology that would answer it
  is written down. Nothing in this repository guarantees today that the port is unreachable.

## Decision

**We refuse `/actuator/metrics`, `/actuator/metrics/**` and `/actuator/prometheus` to every caller,
with or without a token.** This amends ADR-017 and ADR-032 for these routes. Both stay Accepted, and
everything they decide beyond these routes stands.

- **The rule is `denyAll`, placed before the actuator `permitAll`.** `SecurityConfiguration` matches
  the first rule that fits, so the order is what refuses them.
- **Health, liveness, readiness and info stay open.** A deployment cannot start without the probes
  (ADR-017).
- **The exposure list is unchanged.** `management.endpoints.web.exposure.include` still reads
  `health,info,metrics,prometheus`, so the endpoints exist and only the chain stands between them and
  a caller. An endpoint that is not exposed, such as `loggers`, `env` or `heapdump`, still answers
  404, which tells a caller nothing.
- **This is the fail-closed default, not the answer to #78.** Whether and how the metrics reopen,
  whether through a management port reachable only inside the VPC, a scrape credential or a network
  boundary, stays the decision of #78, together with the topology (#153) and Q19.

## Consequences

**Easier**

- **The account-existence signal is closed.** The metrics can no longer be used to tell a real
  sign-in name from an invented one, and a prober can no longer watch for being detected.
- **The metrics stop depending on a guarantee nobody has written down.** Whether the port is reachable
  from outside no longer decides whether they leak.
- **`MetricsHttpTest` names the refusal.** `/actuator/prometheus`, `/actuator/metrics` and
  `/actuator/metrics/hrsched.changes` each answer 401 without a token, and health and info are
  asserted to answer, so narrowing or widening the rule is a build failure.

**Harder**

- **Nothing can read the metrics over HTTP.** Not the operator, not an Admin, not a scraper. The
  meters are still recorded, but until #78 is decided nothing outside the process can read one. That
  costs nothing today, because nothing scrapes them (Q19, #165), and it becomes a cost the day
  something should.
- **`MetricsHttpTest` reads the registry instead of scraping.** That the meters are recorded is
  asserted against `MeterRegistry`, which is a weaker check than the one the scrape gave: it does not
  prove that the Prometheus rendering contains them. The test still needs `@AutoConfigureMetrics`, and
  so a second Spring context, to make the endpoints exist for the refusals to be tested.
- **A caller with no token is answered 401.** That is the case `MetricsHttpTest` asserts. The
  status for a caller who does hold a token is not pinned by a test.
- **Reopening is a code change in `SecurityConfiguration`.** Anyone who wires a scraper has to remove
  or narrow this rule and write down who may reach what, which is the point, but it is not a
  configuration switch.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Remove `metrics` and `prometheus` from the exposure list** | Also closes the read. Rejected because it changes which endpoints exist and not who may read them, so putting them back on the list would undo the decision by accident. A rule in the chain is who may read them, and a test can pin it. |
| **Move Actuator to its own management port** | The usual mitigation, and one property. Rejected for now because it decides topology (which network can reach the port), and that is #78 and #153. **Revisit when** #78 is decided in favour of a port reachable only inside the VPC. |
| **Require a role, such as `ADMIN`, for the metrics** | Needs no new infrastructure. Rejected because there is no scraper identity to grant a role to. **Revisit when** a scrape credential is designed. |

**Revisit when** #78 is decided, or a scraper exists (#165).
