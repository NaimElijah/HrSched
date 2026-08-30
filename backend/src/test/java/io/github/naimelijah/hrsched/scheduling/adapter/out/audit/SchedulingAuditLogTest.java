package io.github.naimelijah.hrsched.scheduling.adapter.out.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SchedulingChange;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Specification of the adapter that turns one recorded change into a log event and a metric.
 *
 * <p>Asserted against Logback directly rather than against the rendered JSON. The fields this class
 * attaches are its own; how they are encoded is Spring Boot's, and a test that pinned the JSON
 * would be testing the framework and would break on a format change that broke nothing.
 *
 * <p>Fields are asserted as key-value pairs, not by matching the message text, because that is the
 * distinction that makes the log queryable rather than greppable.
 */
class SchedulingAuditLogTest {

  private static final OrganisationId ACME = OrganisationId.newId();

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final SchedulingAuditLog trail = new SchedulingAuditLog(meters);

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(SchedulingAuditLog.class.getName());
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
        .tag("context", "scheduling")
        .tag("change", change)
        .counter()
        .count();
  }

  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenTheActorOrganisationAndSubjectAreAllFields() {
    UserId actor = UserId.newId();
    UUID roster = UUID.randomUUID();

    trail.record(new AuditedChange(ACME, actor, SchedulingChange.ROSTER_PUBLISHED, roster));

    assertThat(fieldsOf(onlyEvent()))
        .containsEntry("organisation", ACME.value())
        .containsEntry("actor", actor.value())
        .containsEntry("change", SchedulingChange.ROSTER_PUBLISHED)
        .containsEntry("subject", roster);
  }

  /**
   * INFO, not DEBUG. These are state transitions and they are the record; a trail switched off by
   * default in production is not a trail.
   */
  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenItIsLoggedAtInfo() {
    trail.record(
        new AuditedChange(ACME, UserId.newId(), SchedulingChange.SHIFT_DEFINED, UUID.randomUUID()));

    assertThat(onlyEvent().getLevel()).isEqualTo(Level.INFO);
  }

  /**
   * The guarantee the record's shape exists to give. Names, availability and rosters are personal
   * data; an audit trail quoting them is a second copy of that data somewhere nobody thinks of as a
   * database. Every value written here must be an identifier.
   */
  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenEveryValueIsAnIdentifierAndNeverAName() {
    trail.record(
        new AuditedChange(
            ACME, UserId.newId(), SchedulingChange.ROSTER_GENERATED, UUID.randomUUID()));

    ILoggingEvent event = onlyEvent();
    assertThat(event.getKeyValuePairs())
        .extracting(pair -> pair.value)
        .allSatisfy(
            value ->
                assertThat(value)
                    .describedAs("an audit field must be an identifier or an enum, never free text")
                    .isInstanceOfAny(UUID.class, SchedulingChange.class));
    assertThat(event.getFormattedMessage())
        .describedAs("the message is a constant; everything variable belongs in a field")
        .isEqualTo("Scheduling change recorded");
  }

  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenItIsAlsoCountedByItsKind() {
    trail.record(
        new AuditedChange(
            ACME, UserId.newId(), SchedulingChange.ROSTER_PUBLISHED, UUID.randomUUID()));
    trail.record(
        new AuditedChange(
            ACME, UserId.newId(), SchedulingChange.ROSTER_PUBLISHED, UUID.randomUUID()));
    trail.record(
        new AuditedChange(ACME, UserId.newId(), SchedulingChange.SHIFT_DEFINED, UUID.randomUUID()));

    assertThat(changesTagged("ROSTER_PUBLISHED")).isEqualTo(2.0);
    assertThat(changesTagged("SHIFT_DEFINED")).isEqualTo(1.0);
  }

  /**
   * Cardinality is bounded by the enum, not by the data. A tag carrying an organisation or a roster
   * id would multiply the time series by the number of tenants, which is how a metrics bill and a
   * scrape timeout arrive together.
   */
  @Test
  void GivenChangesInManyOrganisations_WhenCounted_ThenTheTenantIsNotATag() {
    trail.record(
        new AuditedChange(
            OrganisationId.newId(),
            UserId.newId(),
            SchedulingChange.SHIFT_DEFINED,
            UUID.randomUUID()));
    trail.record(
        new AuditedChange(
            OrganisationId.newId(),
            UserId.newId(),
            SchedulingChange.SHIFT_DEFINED,
            UUID.randomUUID()));

    assertThat(meters.find("hrsched.changes").counters())
        .describedAs("one series per kind of change, however many tenants there are")
        .hasSize(1);
    assertThat(changesTagged("SHIFT_DEFINED")).isEqualTo(2.0);
  }

  @Test
  void GivenSeveralChanges_WhenWritten_ThenEachIsItsOwnEvent() {
    Function<SchedulingChange, AuditedChange> entry =
        change -> new AuditedChange(ACME, UserId.newId(), change, UUID.randomUUID());

    trail.record(entry.apply(SchedulingChange.ROSTER_PUBLISHED));
    trail.record(entry.apply(SchedulingChange.ROSTER_SUPERSEDED));

    assertThat(captured.list).hasSize(2);
  }
}
