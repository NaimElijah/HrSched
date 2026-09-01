package io.github.naimelijah.hrsched.access.adapter.out.audit;

import io.github.naimelijah.hrsched.access.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the access audit trail to the log, and counts it.
 *
 * <p>Fields are attached as key-value pairs rather than interpolated into the message, so the ECS
 * encoder publishes them as JSON members that a log store can filter on. The organisation's name is
 * not among them: the record it is given has nowhere to carry one.
 *
 * <p>Named for its context rather than for what it does, because Spring derives a bean name from
 * the simple class name alone, and it would otherwise collide with its two siblings at startup.
 *
 * <p>Nothing is persisted; see ADR-018.
 */
@Component
public class AccessAuditLog implements AuditTrail {

  private static final Logger log = LoggerFactory.getLogger(AccessAuditLog.class);

  /** Shared with the other audit adapters so one query answers "what changed" across contexts. */
  private static final String CHANGES = "hrsched.changes";

  private final MeterRegistry meters;

  /**
   * @param meters where each recorded change is counted
   */
  public AccessAuditLog(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void record(AuditedChange change) {
    log.atInfo()
        .addKeyValue("organisation", change.organisation().value())
        .addKeyValue("actor", change.actor().value())
        .addKeyValue("change", change.change())
        .addKeyValue("subject", change.subject())
        .log("Access change recorded");

    Counter.builder(CHANGES)
        .description("State changes recorded in the audit trail")
        .tag("context", "access")
        .tag("change", change.change().name())
        .register(meters)
        .increment();
  }
}
