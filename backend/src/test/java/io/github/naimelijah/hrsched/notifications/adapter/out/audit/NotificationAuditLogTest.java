package io.github.naimelijah.hrsched.notifications.adapter.out.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.notifications.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.notifications.application.port.out.NotificationChange;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Specification of the adapter that turns one recorded notification change into a log event and a
 * metric.
 *
 * <p>The negative assertion matters most here for the same reason it does in workforce: this
 * context is entirely about who was told what, and that is personal data. No recipient appears in
 * the log at all — the trail answers who caused an entry to exist, and who was told is in the
 * store.
 */
class NotificationAuditLogTest {

  private static final OrganisationId ACME = OrganisationId.newId();

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final NotificationAuditLog trail = new NotificationAuditLog(meters);

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(NotificationAuditLog.class.getName());
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

  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenTheActorOrganisationAndSubjectAreAllFields() {
    UserId actor = UserId.newId();
    UUID notification = UUID.randomUUID();

    trail.record(
        new AuditedChange(ACME, actor, NotificationChange.NOTIFICATION_RAISED, notification));

    assertThat(fieldsOf(onlyEvent()))
        .containsEntry("organisation", ACME.value())
        .containsEntry("actor", actor.value())
        .containsEntry("change", NotificationChange.NOTIFICATION_RAISED)
        .containsEntry("subject", notification);
  }

  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenEveryFieldIsAnIdentifierOrAnEnum() {
    trail.record(
        new AuditedChange(
            ACME, UserId.newId(), NotificationChange.NOTIFICATION_READ, UUID.randomUUID()));

    ILoggingEvent event = onlyEvent();
    assertThat(event.getKeyValuePairs())
        .extracting(pair -> pair.value)
        .allSatisfy(
            value ->
                assertThat(value)
                    .describedAs("an audit field must be an identifier or an enum, never free text")
                    .isInstanceOfAny(UUID.class, NotificationChange.class));
    assertThat(event.getFormattedMessage())
        .describedAs("the message is a constant; everything variable belongs in a field")
        .isEqualTo("Notification change recorded");
  }

  /**
   * The recipient is the one thing this context knows that the log must not. Who was told what
   * lives in the store; the trail says only who caused an entry to exist.
   */
  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenNoRecipientAppearsInIt() {
    trail.record(
        new AuditedChange(
            ACME, UserId.newId(), NotificationChange.NOTIFICATION_RAISED, UUID.randomUUID()));

    assertThat(fieldsOf(onlyEvent()))
        .describedAs("the record has no recipient field, so one cannot appear")
        .doesNotContainKey("recipient");
  }

  @Test
  void GivenARecordedChange_WhenItIsWritten_ThenItIsLoggedAtInfo() {
    trail.record(
        new AuditedChange(
            ACME, UserId.newId(), NotificationChange.NOTIFICATION_RAISED, UUID.randomUUID()));

    assertThat(onlyEvent().getLevel()).isEqualTo(Level.INFO);
  }

  @Test
  void GivenRecordedChanges_WhenCounted_ThenTheyAreSeparatedByKindAndNotByTenant() {
    trail.record(
        new AuditedChange(
            ACME, UserId.newId(), NotificationChange.NOTIFICATION_RAISED, UUID.randomUUID()));
    trail.record(
        new AuditedChange(
            OrganisationId.newId(),
            UserId.newId(),
            NotificationChange.NOTIFICATION_RAISED,
            UUID.randomUUID()));
    trail.record(
        new AuditedChange(
            ACME, UserId.newId(), NotificationChange.NOTIFICATION_READ, UUID.randomUUID()));

    assertThat(
            meters
                .get("hrsched.changes")
                .tag("context", "notifications")
                .tag("change", "NOTIFICATION_RAISED")
                .counter()
                .count())
        .describedAs("two tenants, one series")
        .isEqualTo(2.0);
    assertThat(meters.find("hrsched.changes").counters())
        .describedAs("one series per kind of change, however many tenants there are")
        .hasSize(2);
  }
}
