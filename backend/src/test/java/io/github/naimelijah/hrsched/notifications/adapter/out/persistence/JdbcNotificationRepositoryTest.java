package io.github.naimelijah.hrsched.notifications.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.notifications.domain.model.Notification;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationKind;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Specification of what the notification store promises.
 *
 * <p>Two filters, not one. Every read is scoped by organisation like its siblings, and also by
 * recipient — because an inbox handed to the wrong colleague is a disclosure even inside the right
 * tenant. The tests whose failure would be a security incident are the isolation ones: the two
 * reads that must not cross an organisation or reach a colleague, and the saves that must not write
 * over another organisation's notification.
 */
class JdbcNotificationRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final EmployeeId ADA = EmployeeId.newId();
  private static final EmployeeId GRACE = EmployeeId.newId();

  private final JdbcNotificationRepository notifications =
      new JdbcNotificationRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
    PostgresDatabase.given(ACME, RIVAL);
  }

  private static Notification at(
      OrganisationId organisation, EmployeeId recipient, Instant occurredAt) {
    return new Notification(
        NotificationId.newId(),
        organisation,
        recipient,
        NotificationKind.SWAP_REQUESTED,
        UUID.randomUUID(),
        occurredAt);
  }

  @Test
  void GivenANotification_WhenSaved_ThenItsRecipientFindsIt() {
    Notification saved = at(ACME, ADA, Instant.parse("2026-10-05T12:00:00Z"));

    notifications.save(saved);

    assertThat(notifications.findById(ACME, ADA, saved.id())).contains(saved);
    assertThat(notifications.findFor(ACME, ADA)).containsExactly(saved);
  }

  @Test
  void GivenANotificationForAColleague_WhenWeRead_ThenItIsNotReturned() {
    Notification theirs = at(ACME, GRACE, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(theirs);

    assertThat(notifications.findFor(ACME, ADA)).isEmpty();
    assertThat(notifications.findById(ACME, ADA, theirs.id())).isEmpty();
  }

  @Test
  void GivenANotificationInAnotherOrganisation_WhenWeRead_ThenItIsNotReturned() {
    Notification theirs = at(RIVAL, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(theirs);

    assertThat(notifications.findFor(ACME, ADA)).isEmpty();
    assertThat(notifications.findById(ACME, ADA, theirs.id())).isEmpty();
  }

  /** An inbox nobody can find the new thing in is not an inbox. */
  @Test
  void GivenSeveralNotifications_WhenListed_ThenTheNewestComesFirst() {
    Notification oldest = at(ACME, ADA, Instant.parse("2026-10-05T09:00:00Z"));
    Notification newest = at(ACME, ADA, Instant.parse("2026-10-05T17:00:00Z"));
    Notification middle = at(ACME, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(oldest);
    notifications.save(newest);
    notifications.save(middle);

    assertThat(notifications.findFor(ACME, ADA)).containsExactly(newest, middle, oldest);
  }

  @Test
  void GivenANotificationSavedTwice_WhenListed_ThenThereIsOneOfIt() {
    Notification saved = at(ACME, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(saved);

    notifications.save(saved.markedRead());

    assertThat(notifications.findFor(ACME, ADA))
        .singleElement()
        .extracting(Notification::isRead)
        .isEqualTo(true);
  }

  @Test
  void GivenNothingSaved_WhenListed_ThenNothingIsReturned() {
    assertThat(notifications.findFor(ACME, ADA)).isEmpty();
  }

  /** The time is the caller's, so the store must hand back exactly what it was given. */
  @Test
  void GivenANotification_WhenReadBack_ThenItKeepsTheInstantItWasGiven() {
    Instant told = Instant.parse("2026-10-05T12:00:00Z");
    Notification saved = at(ACME, ADA, told);
    notifications.save(saved);

    assertThat(notifications.findFor(ACME, ADA))
        .singleElement()
        .extracting(Notification::occurredAt)
        .isEqualTo(told);
  }

  /**
   * PostgreSQL keeps microseconds, and pgjdbc rounds an instant half-up to the microsecond as it
   * sends it, so a moment finer than that is kept as the nearest microsecond. That is how a span
   * declared beyond microseconds is kept too ({@code
   * EmployeeHttpTest#GivenASpanDeclaredBeyondMicroseconds_WhenWithdrawing_ThenTheReadBackInstantsNameIt}).
   * The copy read back is then the notification as stored, so saving it marked read is accepted
   * rather than refused as another moment. The invented 600 nanoseconds round up, so a store that
   * truncated would show.
   */
  @Test
  void
      GivenAMomentFinerThanAMicrosecond_WhenReadBackAndAcknowledged_ThenTheNearestMicrosecondIsKept() {
    Instant told = Instant.parse("2026-10-05T12:00:00.000000600Z");
    Instant nearestMicrosecond = Instant.parse("2026-10-05T12:00:00.000001Z");
    Notification saved = at(ACME, ADA, told);
    notifications.save(saved);

    Notification readBack = notifications.findById(ACME, ADA, saved.id()).orElseThrow();
    assertThat(readBack.occurredAt()).isEqualTo(nearestMicrosecond);

    notifications.save(readBack.markedRead());
    assertThat(notifications.findFor(ACME, ADA))
        .singleElement()
        .extracting(Notification::occurredAt, Notification::isRead)
        .containsExactly(nearestMicrosecond, true);
  }

  @Test
  void GivenAnUnreadNotification_WhenReadBack_ThenItIsStillUnread() {
    Notification saved = at(ACME, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(saved);

    assertThat(notifications.findFor(ACME, ADA))
        .singleElement()
        .extracting(Notification::isRead)
        .isEqualTo(false);
  }

  /**
   * {@code save} is the one repository method carrying no {@code OrganisationId}, so the upsert is
   * the only thing standing between a re-used identity and another tenant's row. Ours differs in
   * every column the upsert would overwrite but {@code read}, which is false on both, so an
   * overwrite would show.
   */
  @Test
  void GivenAnIdentityHeldByAnotherOrganisation_WhenSavingOverIt_ThenItIsRefused() {
    Notification theirs = at(RIVAL, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(theirs);

    assertThatThrownBy(() -> notifications.save(overwriting(theirs)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another organisation");
  }

  @Test
  void GivenAnIdentityHeldByAnotherOrganisation_WhenSavingOverIt_ThenTheirRowIsUntouched() {
    Notification theirs = at(RIVAL, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(theirs);

    assertThatThrownBy(() -> notifications.save(overwriting(theirs)))
        .isInstanceOf(IllegalStateException.class);

    // Read through the recipient's inbox, because an overwrite would have moved it to somebody
    // else.
    assertThat(notifications.findFor(RIVAL, ADA))
        .singleElement()
        .extracting(Notification::subject)
        .isEqualTo(theirs.subject());
  }

  /**
   * The organisation half of the guard alone. {@link #overwriting} changes every column at once, so
   * the recipient, kind, subject or moment would refuse it without the organisation being compared
   * at all. Here only the organisation differs, and the save marks it read: were the organisation
   * not compared, the statement would set {@code read} on their row and report success.
   */
  @Test
  void GivenAnIdentityHeldByAnotherOrganisationForTheSameRecipient_WhenSavingOverIt_ThenRefused() {
    Notification theirs = at(RIVAL, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(theirs);
    Notification ours =
        new Notification(
                theirs.id(), ACME, ADA, theirs.kind(), theirs.subject(), theirs.occurredAt())
            .markedRead();

    assertThatThrownBy(() -> notifications.save(ours)).isInstanceOf(IllegalStateException.class);

    assertThat(notifications.findFor(RIVAL, ADA))
        .singleElement()
        .extracting(Notification::isRead)
        .describedAs("their notification must not have been marked read by our save")
        .isEqualTo(false);
    assertThat(notifications.findFor(ACME, ADA)).isEmpty();
  }

  // --- #250: within one organisation, a notification only ever becomes read --------------------

  /**
   * Being marked read is the one change the domain makes to a notification. A save naming another
   * recipient would hand it to a colleague: their inbox would show what happened to somebody else,
   * and the person it was for would never see it. Refused as a save over another organisation's
   * identity is, so it cannot pass in silence.
   */
  @Test
  void
      GivenAColleagueAsRecipient_WhenSavingOverANotification_ThenItIsRefusedAndStaysWithItsOwner() {
    Notification adas = at(ACME, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(adas);
    Notification movedToGrace =
        new Notification(adas.id(), ACME, GRACE, adas.kind(), adas.subject(), adas.occurredAt());

    assertThatThrownBy(() -> notifications.save(movedToGrace))
        .isInstanceOf(IllegalStateException.class);

    assertThat(notifications.findFor(ACME, ADA)).containsExactly(adas);
    assertThat(notifications.findFor(ACME, GRACE)).isEmpty();
  }

  /**
   * Somebody who has seen a notification has seen it: an unread copy saved afterwards, a stale one
   * read before the acknowledgement, must not put it back in front of them as new.
   */
  @Test
  void GivenAReadNotification_WhenAnUnreadCopyIsSaved_ThenItIsStillRead() {
    Notification unread = at(ACME, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(unread);
    notifications.save(unread.markedRead());

    notifications.save(unread);

    assertThat(notifications.findFor(ACME, ADA))
        .singleElement()
        .extracting(Notification::isRead)
        .isEqualTo(true);
  }

  // --- #273, finding 3: what somebody was told is never rewritten -----------------------------

  /**
   * A notification's kind, subject and moment are what somebody was told, and the aggregate holds
   * each as final: a save reusing a stored identity in the same inbox but changing one would
   * silently rewrite it. Refused as a recipient change is, so it cannot pass in silence.
   */
  @Test
  void GivenAnotherKind_WhenSavingOverANotification_ThenItIsRefusedAndTheStoredOneIsUnchanged() {
    Notification stored = at(ACME, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(stored);

    assertRefusedAndUnchanged(
        stored,
        new Notification(
            stored.id(),
            ACME,
            ADA,
            NotificationKind.SWAP_APPROVED,
            stored.subject(),
            stored.occurredAt()));
  }

  @Test
  void GivenAnotherSubject_WhenSavingOverANotification_ThenItIsRefusedAndTheStoredOneIsUnchanged() {
    Notification stored = at(ACME, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(stored);

    assertRefusedAndUnchanged(
        stored,
        new Notification(
            stored.id(), ACME, ADA, stored.kind(), UUID.randomUUID(), stored.occurredAt()));
  }

  @Test
  void GivenAnotherMoment_WhenSavingOverANotification_ThenItIsRefusedAndTheStoredOneIsUnchanged() {
    Notification stored = at(ACME, ADA, Instant.parse("2026-10-05T12:00:00Z"));
    notifications.save(stored);

    assertRefusedAndUnchanged(
        stored,
        new Notification(
            stored.id(),
            ACME,
            ADA,
            stored.kind(),
            stored.subject(),
            Instant.parse("2026-10-06T12:00:00Z")));
  }

  /**
   * Saving {@code changed} over {@code stored} is refused, and the recipient's inbox still holds
   * {@code stored} as it was: equality is by identity alone, so each column is compared.
   */
  private void assertRefusedAndUnchanged(Notification stored, Notification changed) {
    assertThatThrownBy(() -> notifications.save(changed)).isInstanceOf(IllegalStateException.class);

    assertThat(notifications.findFor(ACME, ADA))
        .singleElement()
        .extracting(
            Notification::kind,
            Notification::subject,
            Notification::occurredAt,
            Notification::isRead)
        .containsExactly(stored.kind(), stored.subject(), stored.occurredAt(), stored.isRead());
  }

  private static Notification overwriting(Notification theirs) {
    return new Notification(
        theirs.id(),
        ACME,
        GRACE,
        NotificationKind.SWAP_APPROVED,
        UUID.randomUUID(),
        Instant.parse("2026-10-06T12:00:00Z"));
  }
}
