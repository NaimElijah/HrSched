package io.github.naimelijah.hrsched.access.adapter.out.audit;

import io.github.naimelijah.hrsched.access.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * Writes the access audit trail to the log, and counts it.
 *
 * <p>Fields are attached as key-value pairs rather than interpolated into the message, so the ECS
 * encoder publishes them as JSON members that a log store can filter on. The organisation's name is
 * not among them: the record it is given has nowhere to carry one.
 *
 * <p>A change belonging to no organisation - the platform operator's own - is written without the
 * field rather than with a null one.
 *
 * <p>Named for its context rather than for what it does, because Spring derives a bean name from
 * the simple class name alone, and it would otherwise collide with its three siblings at startup.
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
    // The organisation field is left out rather than written as null when there is none, so that
    // "belongs to no tenant" and "was not recorded" stay different things to whoever queries this.
    LoggingEventBuilder event =
        log.atInfo()
            .addKeyValue("actor", change.actor().value())
            .addKeyValue("change", change.change())
            .addKeyValue("subject", change.subject());
    change
        .organisation()
        .map(OrganisationId::value)
        .ifPresent(organisation -> event.addKeyValue("organisation", organisation));
    event.log("Access change recorded");

    Counter.builder(CHANGES)
        .description("State changes recorded in the audit trail")
        .tag("context", "access")
        .tag("change", change.change().name())
        .register(meters)
        .increment();
  }
}
