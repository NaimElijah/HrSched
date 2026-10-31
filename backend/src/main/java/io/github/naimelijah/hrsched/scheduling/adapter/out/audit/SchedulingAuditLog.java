package io.github.naimelijah.hrsched.scheduling.adapter.out.audit;

import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditedChange;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the scheduling audit trail to the log, and counts it.
 *
 * <p>One call becomes both a structured event and a metric, which is why scheduling needs no
 * separate port for instrumentation. The log answers "who changed this roster"; the counter answers
 * "how often does that happen", and neither question is well served by the other's data.
 *
 * <p>Fields are attached as key-value pairs rather than interpolated into the message, so the ECS
 * encoder publishes them as JSON members that a log store can filter on. A message that has to be
 * parsed with a regular expression is not structured logging.
 *
 * <p>INFO, not DEBUG: these are state transitions, and they are the record. A trail that is off by
 * default in production is not a trail.
 *
 * <p>Named for its context rather than for what it does, because Spring derives a bean name from
 * the simple class name alone: two adapters called {@code LoggingAuditTrail} in different packages
 * collide, and they do so at startup rather than at compile time.
 *
 * <p>Nothing is persisted. Whether this should be a durable, queryable trail is Q20; see ADR-018.
 */
@Component
public class SchedulingAuditLog implements AuditTrail {

  private static final Logger log = LoggerFactory.getLogger(SchedulingAuditLog.class);

  /**
   * Shared with every other context's audit adapter, told apart by the {@code context} tag, so one
   * query answers "what changed" across all of them.
   */
  private static final String CHANGES = "hrsched.changes";

  private final MeterRegistry meters;

  /**
   * @param meters where each recorded change is counted
   */
  public SchedulingAuditLog(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void record(AuditedChange change) {
    log.atInfo()
        .addKeyValue("organisation", change.organisation().value())
        .addKeyValue("actor", change.actor().value())
        .addKeyValue("change", change.change())
        .addKeyValue("subject", change.subject())
        .log("Scheduling change recorded");

    Counter.builder(CHANGES)
        .description("State changes recorded in the audit trail")
        .tag("context", "scheduling")
        .tag("change", change.change().name())
        .register(meters)
        .increment();
  }
}
