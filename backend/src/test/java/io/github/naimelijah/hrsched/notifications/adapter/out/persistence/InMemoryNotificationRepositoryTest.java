package io.github.naimelijah.hrsched.notifications.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.notifications.domain.model.Notification;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationKind;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Specification of what the notification store promises.
 *
 * <p>Two filters, not one. Every read is scoped by organisation like its siblings, and also by
 * recipient — because an inbox handed to the wrong colleague is a disclosure even inside the right
 * tenant. The tests whose failure would be a security incident are the two isolation ones.
 */
class InMemoryNotificationRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final EmployeeId ADA = EmployeeId.newId();
  private static final EmployeeId GRACE = EmployeeId.newId();

  private final InMemoryNotificationRepository notifications = new InMemoryNotificationRepository();

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
}
