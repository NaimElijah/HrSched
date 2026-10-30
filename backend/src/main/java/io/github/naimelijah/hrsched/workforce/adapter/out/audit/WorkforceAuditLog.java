package io.github.naimelijah.hrsched.workforce.adapter.out.audit;

import io.github.naimelijah.hrsched.workforce.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditedChange;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the workforce audit trail to the log, and counts it.
 *
 * <p>One call becomes both a structured event and a metric, which is why workforce needs no
 * separate port for instrumentation.
 *
 * <p>Fields are attached as key-value pairs rather than interpolated into the message, so the ECS
 * encoder publishes them as JSON members that a log store can filter on. Employee names never
 * appear: the record it is given has nowhere to carry one.
 *
 * <p>Named for its context rather than for what it does, because Spring derives a bean name from
 * the simple class name alone: two adapters called {@code LoggingAuditTrail} in different packages
 * collide, and they do so at startup rather than at compile time.
 *
 * <p>Nothing is persisted (ADR-018). Q20 is answered: the trail is to be durable, and an Admin is
 * to read their own organisation's and nobody else's, which #31 builds. How long it is kept is Q23.
 */
@Component
public class WorkforceAuditLog implements AuditTrail {

  private static final Logger log = LoggerFactory.getLogger(WorkforceAuditLog.class);

  /**
   * Shared with the other three contexts' audit logs, so one query answers "what changed" across
   * all four.
   */
  private static final String CHANGES = "hrsched.changes";

  private final MeterRegistry meters;

  /**
   * @param meters where each recorded change is counted
   */
  public WorkforceAuditLog(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void record(AuditedChange change) {
    log.atInfo()
        .addKeyValue("organisation", change.organisation().value())
        .addKeyValue("actor", change.actor().value())
        .addKeyValue("change", change.change())
        .addKeyValue("subject", change.subject())
        .log("Workforce change recorded");

    Counter.builder(CHANGES)
        .description("State changes recorded in the audit trail")
        .tag("context", "workforce")
        .tag("change", change.change().name())
        .register(meters)
        .increment();
  }
}
