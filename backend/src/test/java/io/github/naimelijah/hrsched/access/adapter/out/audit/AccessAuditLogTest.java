package io.github.naimelijah.hrsched.access.adapter.out.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Specification of the adapter that turns one recorded access change into a log event and a metric.
 *
 * <p>An organisation's name is not personal data, so the reason nothing but identifiers is written
 * here is different from its workforce twin: it is that the three trails must stay one queryable
 * shape. A field that is free text in one of them and an identifier in the other two is a trail
 * nobody can search across.
 *
 * <p>Asserted against Logback rather than the rendered JSON: the fields are this class's, the
 * encoding is Spring Boot's.
 */
class AccessAuditLogTest {

  private static final OrganisationId ACME = OrganisationId.newId();

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final AccessAuditLog trail = new AccessAuditLog(meters);

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(AccessAuditLog.class.getName());
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
        .tag("context", "access")
        .tag("change", change)
        .counter()
        .count();
  }

  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenTheActorOrganisationAndSubjectAreAllFields() {
    UserId actor = UserId.newId();

    trail.record(new AuditedChange(ACME, actor, AccessChange.ORGANISATION_ONBOARDED, ACME.value()));

    assertThat(fieldsOf(onlyEvent()))
        .containsEntry("organisation", ACME.value())
        .containsEntry("actor", actor.value())
        .containsEntry("change", AccessChange.ORGANISATION_ONBOARDED)
        .containsEntry("subject", ACME.value());
  }

  @Test
  void GivenAnOnboarding_WhenItIsWritten_ThenEveryFieldIsAnIdentifierOrAnEnum() {
    trail.record(
        new AuditedChange(ACME, UserId.newId(), AccessChange.ORGANISATION_ONBOARDED, ACME.value()));

    ILoggingEvent event = onlyEvent();
    assertThat(event.getKeyValuePairs())
        .extracting(pair -> pair.value)
        .allSatisfy(
            value ->
                assertThat(value)
                    .describedAs("an audit field must be an identifier or an enum, never free text")
                    .isInstanceOfAny(java.util.UUID.class, AccessChange.class));
    assertThat(event.getFormattedMessage())
        .describedAs("the message is a constant; everything variable belongs in a field")
        .isEqualTo("Access change recorded");
  }

  /**
   * The name the operator typed is the one thing this adapter could leak and its siblings could
   * not, because the organisation it is recording has one. It never reaches the record, so it can
   * never reach the log.
   */
  @Test
  void GivenAnOnboarding_WhenItIsWritten_ThenTheOrganisationsNameIsNowhereInIt() {
    trail.record(
        new AuditedChange(ACME, UserId.newId(), AccessChange.ORGANISATION_ONBOARDED, ACME.value()));

    assertThat(onlyEvent().toString()).doesNotContain("Acme");
  }

  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenItIsLoggedAtInfo() {
    trail.record(
        new AuditedChange(ACME, UserId.newId(), AccessChange.ORGANISATION_ONBOARDED, ACME.value()));

    assertThat(onlyEvent().getLevel()).isEqualTo(Level.INFO);
  }

  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenItIsAlsoCountedByItsKind() {
    trail.record(
        new AuditedChange(ACME, UserId.newId(), AccessChange.ORGANISATION_ONBOARDED, ACME.value()));
    trail.record(
        new AuditedChange(ACME, UserId.newId(), AccessChange.ORGANISATION_ONBOARDED, ACME.value()));

    assertThat(changesTagged("ORGANISATION_ONBOARDED")).isEqualTo(2.0);
  }

  /**
   * The counter is tagged by context as well as by kind, so one query spans all three trails while
   * each remains separable. Cardinality stays bounded by two enums rather than by the number of
   * tenants — which matters most here, where every entry names a different organisation.
   */
  @Test
  void GivenOnboardingsOfManyOrganisations_WhenCounted_ThenTheTenantIsNotATag() {
    OrganisationId first = OrganisationId.newId();
    OrganisationId second = OrganisationId.newId();

    trail.record(
        new AuditedChange(
            first, UserId.newId(), AccessChange.ORGANISATION_ONBOARDED, first.value()));
    trail.record(
        new AuditedChange(
            second, UserId.newId(), AccessChange.ORGANISATION_ONBOARDED, second.value()));

    assertThat(meters.find("hrsched.changes").counters())
        .describedAs("one series per kind of change, however many tenants there are")
        .hasSize(1);
    assertThat(changesTagged("ORGANISATION_ONBOARDED")).isEqualTo(2.0);
  }
}
