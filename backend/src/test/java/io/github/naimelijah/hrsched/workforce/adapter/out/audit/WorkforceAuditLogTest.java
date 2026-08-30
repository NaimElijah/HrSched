package io.github.naimelijah.hrsched.workforce.adapter.out.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.workforce.application.port.out.WorkforceChange;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Specification of the adapter that turns one recorded workforce change into a log event and a
 * metric.
 *
 * <p>This is the context where the personal data lives — names, absence, contracted hours — so the
 * assertion that matters most here is the negative one: none of it reaches the log. The record it
 * is handed has nowhere to carry a name, and these tests are what stops that changing quietly.
 *
 * <p>Asserted against Logback rather than the rendered JSON: the fields are this class's, the
 * encoding is Spring Boot's.
 */
class WorkforceAuditLogTest {

  private static final OrganisationId ACME = OrganisationId.newId();

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final WorkforceAuditLog trail = new WorkforceAuditLog(meters);

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(WorkforceAuditLog.class.getName());
    captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(captured);
  }

  private ILoggingEvent onlyEvent() {
    assertThat(captured.list).hasSize(1);
    return captured.list.getFirst();
  }

  private Map<String, Object> fieldsOf(ILoggingEvent event) {
    return event.getKeyValuePairs().stream()
        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
  }

  private double changesTagged(String change) {
    return meters
        .get("hrsched.changes")
        .tag("context", "workforce")
        .tag("change", change)
        .counter()
        .count();
  }

  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenTheActorOrganisationAndSubjectAreAllFields() {
    UserId actor = UserId.newId();
    EmployeeId subject = EmployeeId.newId();

    trail.record(new AuditedChange(ACME, actor, WorkforceChange.CONTRACT_SET, subject.value()));

    assertThat(fieldsOf(onlyEvent()))
        .containsEntry("organisation", ACME.value())
        .containsEntry("actor", actor.value())
        .containsEntry("change", WorkforceChange.CONTRACT_SET)
        .containsEntry("subject", subject.value());
  }

  /**
   * The guarantee this context most needs. Absence and contracted hours say things about a person's
   * health and their household, and a log is copied to places a database is not. Every value here
   * must be an identifier, so that reading the trail tells you who to ask rather than the answer.
   */
  @Test
  void GivenAChangeToSomeonesRecord_WhenItIsWritten_ThenNothingWrittenIsPersonalData() {
    trail.record(
        new AuditedChange(
            ACME, UserId.newId(), WorkforceChange.UNAVAILABILITY_DECLARED, UUID.randomUUID()));

    ILoggingEvent event = onlyEvent();
    assertThat(event.getKeyValuePairs())
        .extracting(pair -> pair.value)
        .allSatisfy(
            value ->
                assertThat(value)
                    .describedAs("an audit field must be an identifier or an enum, never free text")
                    .isInstanceOfAny(UUID.class, WorkforceChange.class));
    assertThat(event.getFormattedMessage())
        .describedAs("the message is a constant; everything variable belongs in a field")
        .isEqualTo("Workforce change recorded");
  }

  /**
   * INFO, not DEBUG. These are state transitions and they are the record; a trail switched off by
   * default in production is not a trail.
   */
  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenItIsLoggedAtInfo() {
    trail.record(
        new AuditedChange(ACME, UserId.newId(), WorkforceChange.EMPLOYEE_ADDED, UUID.randomUUID()));

    assertThat(onlyEvent().getLevel()).isEqualTo(Level.INFO);
  }

  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenItIsAlsoCountedByItsKind() {
    trail.record(
        new AuditedChange(ACME, UserId.newId(), WorkforceChange.EMPLOYEE_ADDED, UUID.randomUUID()));
    trail.record(
        new AuditedChange(ACME, UserId.newId(), WorkforceChange.EMPLOYEE_ADDED, UUID.randomUUID()));
    trail.record(
        new AuditedChange(
            ACME, UserId.newId(), WorkforceChange.PREFERENCE_STATED, UUID.randomUUID()));

    assertThat(changesTagged("EMPLOYEE_ADDED")).isEqualTo(2.0);
    assertThat(changesTagged("PREFERENCE_STATED")).isEqualTo(1.0);
  }

  /**
   * The counter is tagged by context as well as by kind, so one query spans both trails while each
   * remains separable. Cardinality stays bounded by two enums rather than by the number of tenants.
   */
  @Test
  void GivenChangesInManyOrganisations_WhenCounted_ThenTheTenantIsNotATag() {
    trail.record(
        new AuditedChange(
            OrganisationId.newId(),
            UserId.newId(),
            WorkforceChange.EMPLOYEE_ADDED,
            UUID.randomUUID()));
    trail.record(
        new AuditedChange(
            OrganisationId.newId(),
            UserId.newId(),
            WorkforceChange.EMPLOYEE_ADDED,
            UUID.randomUUID()));

    assertThat(meters.find("hrsched.changes").counters())
        .describedAs("one series per kind of change, however many tenants there are")
        .hasSize(1);
    assertThat(changesTagged("EMPLOYEE_ADDED")).isEqualTo(2.0);
  }
}
