package io.github.naimelijah.hrsched.notifications.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Invariants a notification owns.
 *
 * <p>The one worth stating in a test rather than a comment: there is nowhere in this type to put a
 * name or a sentence, so a notification store cannot quietly become a second copy of the roster
 * with everybody's business written out. That is asserted by reflection over the record's own
 * shape, because a future field is exactly what would break it.
 */
class NotificationTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final EmployeeId ADA = EmployeeId.newId();
  private static final Instant NOON = Instant.parse("2026-10-05T12:00:00Z");

  private static Notification unread() {
    return new Notification(
        NotificationId.newId(),
        ACME,
        ADA,
        NotificationKind.SWAP_REQUESTED,
        UUID.randomUUID(),
        NOON);
  }

  @Test
  void GivenANewNotification_WhenCreated_ThenItIsUnread() {
    assertThat(unread().isRead()).isFalse();
  }

  @Test
  void GivenAnUnreadNotification_WhenMarkedRead_ThenItIsRead() {
    assertThat(unread().markedRead().isRead()).isTrue();
  }

  @Test
  void GivenANotification_WhenMarkedRead_ThenTheOriginalIsUnchanged() {
    Notification notification = unread();

    notification.markedRead();

    assertThat(notification.isRead())
        .describedAs("aggregates are immutable, so nothing is ever observed half-updated")
        .isFalse();
  }

  @Test
  void GivenTwoNotificationsWithTheSameContent_WhenCompared_ThenTheyAreStillDifferentThings() {
    UUID subject = UUID.randomUUID();
    NotificationId first = NotificationId.newId();
    NotificationId second = NotificationId.newId();

    assertThat(new Notification(first, ACME, ADA, NotificationKind.SWAP_APPROVED, subject, NOON))
        .isNotEqualTo(
            new Notification(second, ACME, ADA, NotificationKind.SWAP_APPROVED, subject, NOON));
  }

  @Test
  void GivenTwoNotificationsSharingAnIdentity_WhenCompared_ThenTheyAreTheSameThing() {
    NotificationId id = NotificationId.newId();

    assertThat(
            new Notification(
                id, ACME, ADA, NotificationKind.SWAP_APPROVED, UUID.randomUUID(), NOON))
        .isEqualTo(
            new Notification(
                id, ACME, ADA, NotificationKind.SWAP_REJECTED, UUID.randomUUID(), NOON));
  }

  /** One constructor argument left null in each row, every other one present. */
  private static Stream<ThrowingCallable> constructionsMissingOneField() {
    NotificationKind kind = NotificationKind.SWAP_REQUESTED;
    return Stream.of(
        () -> new Notification(null, ACME, ADA, kind, UUID.randomUUID(), NOON),
        () -> new Notification(NotificationId.newId(), null, ADA, kind, UUID.randomUUID(), NOON),
        () -> new Notification(NotificationId.newId(), ACME, null, kind, UUID.randomUUID(), NOON),
        () -> new Notification(NotificationId.newId(), ACME, ADA, null, UUID.randomUUID(), NOON),
        () -> new Notification(NotificationId.newId(), ACME, ADA, kind, null, NOON),
        () -> new Notification(NotificationId.newId(), ACME, ADA, kind, UUID.randomUUID(), null));
  }

  @ParameterizedTest
  @MethodSource("constructionsMissingOneField")
  void GivenANullField_WhenCreatingANotification_ThenItIsRejected(ThrowingCallable creating) {
    assertThatThrownBy(creating).isInstanceOf(NullPointerException.class);
  }

  /**
   * The guarantee, made structurally. Every field is an identifier, an enum, an instant or a flag —
   * so a name cannot be recorded here, rather than merely being discouraged from it.
   */
  @Test
  void GivenTheNotificationType_WhenItsFieldsAreInspected_ThenNoneOfThemCanHoldText() {
    assertThat(Notification.class.getDeclaredFields())
        .filteredOn(field -> !field.isSynthetic())
        .allSatisfy(
            field ->
                assertThat(field.getType())
                    .describedAs(
                        "a notification field must be an identifier, an enum, a time or a flag,"
                            + " never free text: %s",
                        field.getName())
                    .isIn(
                        NotificationId.class,
                        OrganisationId.class,
                        EmployeeId.class,
                        NotificationKind.class,
                        UUID.class,
                        Instant.class,
                        boolean.class));
  }

  @Test
  void GivenANotification_WhenDescribed_ThenItNamesNeitherItsRecipientNorItsSubject() {
    Notification notification = unread();

    assertThat(notification.toString())
        .doesNotContain(ADA.value().toString())
        .doesNotContain(notification.subject().toString());
  }
}
