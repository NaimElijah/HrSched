package io.github.naimelijah.hrsched.notifications.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.notifications.application.port.in.NotifiableEvent;
import io.github.naimelijah.hrsched.notifications.application.port.in.NotifyCommand;
import io.github.naimelijah.hrsched.notifications.application.port.in.PublishedNotification;
import io.github.naimelijah.hrsched.notifications.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.notifications.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.notifications.application.port.out.NotificationChange;
import io.github.naimelijah.hrsched.notifications.application.port.out.NotificationRepository;
import io.github.naimelijah.hrsched.notifications.domain.model.NotAddressableException;
import io.github.naimelijah.hrsched.notifications.domain.model.Notification;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationKind;
import io.github.naimelijah.hrsched.notifications.domain.model.UnknownNotificationException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Specification of the notification use cases, driven without Spring.
 *
 * <p>The tests that matter here are the ones about whose inbox is being read. A notification says
 * that something happened to a named person, so handing one to the wrong reader is a disclosure
 * rather than a bug — and because the recipient is taken from the acting identity rather than from
 * a parameter, most of those tests are about the fact that the wrong request cannot be expressed.
 *
 * <p>The clock is fixed. When a notification was raised is an input here, not an ambient fact.
 */
class NotificationServiceTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final Instant NOON = Instant.parse("2026-10-05T12:00:00Z");
  private static final EmployeeId ADA = EmployeeId.newId();
  private static final EmployeeId GRACE = EmployeeId.newId();

  private final StubNotificationRepository notifications = new StubNotificationRepository();
  private final RecordingAuditTrail audit = new RecordingAuditTrail();
  private final NotificationService service =
      new NotificationService(notifications, audit, Clock.fixed(NOON, ZoneOffset.UTC));

  private static TenantActor themself(OrganisationId organisation, EmployeeId employee) {
    return new TenantActor(UserId.newId(), organisation, Role.EMPLOYEE, Optional.of(employee));
  }

  private NotificationId notifyAda(UUID subject) {
    return service.notify(
        themself(ACME, GRACE),
        new NotifyCommand(ACME, ADA, NotifiableEvent.SWAP_REQUESTED, subject));
  }

  @Test
  void GivenSomethingHappened_WhenSomebodyIsNotified_ThenTheyFindItInTheirInbox() {
    UUID subject = UUID.randomUUID();

    NotificationId raised = notifyAda(subject);

    assertThat(service.findMine(themself(ACME, ADA), ACME))
        .containsExactly(
            new PublishedNotification(
                raised, NotifiableEvent.SWAP_REQUESTED, subject, NOON, false));
  }

  /** R33: a swap whose roster was replaced before anybody decided it is told to its parties. */
  @Test
  void GivenASwapThatLapsed_WhenItsPartyIsNotified_ThenTheirInboxSaysItLapsed() {
    UUID subject = UUID.randomUUID();

    NotificationId raised =
        service.notify(
            themself(ACME, GRACE),
            new NotifyCommand(ACME, ADA, NotifiableEvent.SWAP_LAPSED, subject));

    assertThat(service.findMine(themself(ACME, ADA), ACME))
        .containsExactly(
            new PublishedNotification(raised, NotifiableEvent.SWAP_LAPSED, subject, NOON, false));
  }

  /** The clock is injected, so this is a fact the test states rather than one it tolerates. */
  @Test
  void GivenAFixedClock_WhenSomebodyIsNotified_ThenTheNotificationCarriesThatMoment() {
    notifyAda(UUID.randomUUID());

    assertThat(service.findMine(themself(ACME, ADA), ACME))
        .singleElement()
        .extracting(PublishedNotification::occurredAt)
        .isEqualTo(NOON);
  }

  /**
   * The disclosure this context could most easily cause. Whose inbox is read comes from the acting
   * identity, so a colleague's notifications are not so much refused as unrepresentable.
   */
  @Test
  void GivenANotificationForSomebodyElse_WhenReadingOursOwn_ThenItIsNotThere() {
    notifyAda(UUID.randomUUID());

    assertThat(service.findMine(themself(ACME, GRACE), ACME)).isEmpty();
  }

  @Test
  void GivenANotificationInAnotherOrganisation_WhenReadingOurs_ThenItIsNotThere() {
    service.notify(
        themself(RIVAL, GRACE),
        new NotifyCommand(RIVAL, ADA, NotifiableEvent.SWAP_REQUESTED, UUID.randomUUID()));

    assertThat(service.findMine(themself(ACME, ADA), ACME)).isEmpty();
  }

  @Test
  void GivenAnActorOfAnotherOrganisation_WhenReadingOurNotifications_ThenItIsRefused() {
    notifyAda(UUID.randomUUID());

    assertThatThrownBy(() -> service.findMine(themself(RIVAL, ADA), ACME))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnActorOfAnotherOrganisation_WhenNotifyingIntoOurs_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.notify(
                    themself(RIVAL, GRACE),
                    new NotifyCommand(
                        ACME, ADA, NotifiableEvent.SWAP_REQUESTED, UUID.randomUUID())))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnActorWithNoPersonnelRecord_WhenReadingNotifications_ThenItIsRefused() {
    assertThatThrownBy(
            () -> service.findMine(new TenantActor(UserId.newId(), ACME, Role.ADMIN), ACME))
        .isInstanceOf(NotAddressableException.class);
  }

  /**
   * Still refused, and now refused sooner and for a better reason: not "you have no inbox" but "you
   * are not a member of this organisation at all" (ADR-030).
   */
  @Test
  void GivenTheOperator_WhenReadingNotifications_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findMine(new Operator(UserId.newId()), ACME))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenAnUnreadNotification_WhenAcknowledged_ThenItIsMarkedRead() {
    NotificationId raised = notifyAda(UUID.randomUUID());

    service.markRead(themself(ACME, ADA), ACME, raised);

    assertThat(service.findMine(themself(ACME, ADA), ACME))
        .singleElement()
        .extracting(PublishedNotification::read)
        .isEqualTo(true);
  }

  /** Two open tabs acknowledge the same thing twice; that is not an error. */
  @Test
  void GivenAnAlreadyReadNotification_WhenAcknowledgedAgain_ThenItIsStillRead() {
    NotificationId raised = notifyAda(UUID.randomUUID());
    service.markRead(themself(ACME, ADA), ACME, raised);

    service.markRead(themself(ACME, ADA), ACME, raised);

    assertThat(service.findMine(themself(ACME, ADA), ACME))
        .singleElement()
        .extracting(PublishedNotification::read)
        .isEqualTo(true);
  }

  /** Somebody else's notification and one that never existed must be the same answer. */
  @Test
  void GivenSomebodyElsesNotification_WhenAcknowledgingIt_ThenItIsReportedAsAbsent() {
    NotificationId adas = notifyAda(UUID.randomUUID());

    assertThatThrownBy(() -> service.markRead(themself(ACME, GRACE), ACME, adas))
        .isInstanceOf(UnknownNotificationException.class);
  }

  @Test
  void GivenNoSuchNotification_WhenAcknowledgingIt_ThenItIsRefused() {
    assertThatThrownBy(() -> service.markRead(themself(ACME, ADA), ACME, NotificationId.newId()))
        .isInstanceOf(UnknownNotificationException.class);
  }

  @Test
  void GivenSomebodyIsNotified_WhenItIsRaised_ThenTheChangeIsRecordedAgainstTheCaller() {
    TenantActor grace = themself(ACME, GRACE);

    NotificationId raised =
        service.notify(
            grace, new NotifyCommand(ACME, ADA, NotifiableEvent.SWAP_REQUESTED, UUID.randomUUID()));

    assertThat(audit.of(NotificationChange.NOTIFICATION_RAISED))
        .containsExactly(
            new AuditedChange(
                ACME, grace.id(), NotificationChange.NOTIFICATION_RAISED, raised.value()));
  }

  @Test
  void GivenANotification_WhenAcknowledged_ThenThatIsRecordedToo() {
    NotificationId raised = notifyAda(UUID.randomUUID());
    TenantActor ada = themself(ACME, ADA);

    service.markRead(ada, ACME, raised);

    assertThat(audit.of(NotificationChange.NOTIFICATION_READ))
        .containsExactly(
            new AuditedChange(
                ACME, ada.id(), NotificationChange.NOTIFICATION_READ, raised.value()));
  }

  @Test
  void GivenARefusedAcknowledgement_WhenItIsRejected_ThenNothingIsRecordedForIt() {
    notifyAda(UUID.randomUUID());

    assertThatThrownBy(() -> service.markRead(themself(ACME, ADA), ACME, NotificationId.newId()))
        .isInstanceOf(UnknownNotificationException.class);

    assertThat(audit.changes()).containsExactly(NotificationChange.NOTIFICATION_RAISED);
  }

  /**
   * #250. The double stands in for {@code JdbcNotificationRepository}, which refuses a save over an
   * identity another organisation holds. A double that accepted it would let a use case writing
   * across the tenant boundary pass every test here while the database refused it in production.
   */
  @Test
  void
      GivenAnIdentityHeldByAnotherOrganisation_WhenTheRepositoryDoubleSavesOverIt_ThenItIsRefused() {
    Notification theirs =
        new Notification(
            NotificationId.newId(),
            RIVAL,
            ADA,
            NotificationKind.SWAP_REQUESTED,
            UUID.randomUUID(),
            NOON);
    notifications.save(theirs);

    assertThatThrownBy(
            () ->
                notifications.save(
                    new Notification(
                        theirs.id(),
                        ACME,
                        GRACE,
                        NotificationKind.SWAP_APPROVED,
                        UUID.randomUUID(),
                        NOON)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(notifications.findFor(RIVAL, ADA)).containsExactly(theirs);
    assertThat(notifications.findFor(ACME, GRACE)).isEmpty();
  }

  /**
   * Hand-written, like every other double here: the repository is our own port. It copies the real
   * adapter's refusals, because a double accepting what the database refuses tests nothing.
   */
  private static final class StubNotificationRepository implements NotificationRepository {

    private final Map<NotificationId, Notification> saved = new HashMap<>();

    /**
     * @throws IllegalStateException if the identity already belongs to another organisation or
     *     recipient, as {@code JdbcNotificationRepository}'s upsert refuses it
     */
    @Override
    public void save(Notification notification) {
      Notification held = saved.get(notification.id());
      if (held != null
          && !(held.organisation().equals(notification.organisation())
              && held.recipient().equals(notification.recipient()))) {
        throw new IllegalStateException(
            "Refused to store %s: that identity already belongs to another organisation or recipient"
                .formatted(notification.id().value()));
      }
      saved.put(notification.id(), notification);
    }

    @Override
    public Optional<Notification> findById(
        OrganisationId organisation, EmployeeId recipient, NotificationId id) {
      return Optional.ofNullable(saved.get(id))
          .filter(
              notification ->
                  notification.organisation().equals(organisation)
                      && notification.isFor(recipient));
    }

    /**
     * Deliberately unsorted: ordering is the real adapter's promise, and is asserted against it.
     */
    @Override
    public List<Notification> findFor(OrganisationId organisation, EmployeeId recipient) {
      return saved.values().stream()
          .filter(
              notification ->
                  notification.organisation().equals(organisation) && notification.isFor(recipient))
          .toList();
    }
  }

  /**
   * Records what a notification use case says was changed.
   *
   * <p>A fourth copy rather than a shared one, for the reason ADR-008 gives: it implements this
   * context's port over this context's change vocabulary.
   */
  private static final class RecordingAuditTrail implements AuditTrail {

    private final List<AuditedChange> recorded = new ArrayList<>();

    @Override
    public void record(AuditedChange change) {
      recorded.add(change);
    }

    List<NotificationChange> changes() {
      return recorded.stream().map(AuditedChange::change).toList();
    }

    List<AuditedChange> of(NotificationChange change) {
      return recorded.stream().filter(entry -> entry.change() == change).toList();
    }
  }

  // --- ADR-030: the platform operator is refused by every tenant use case -------------------

  @Test
  void GivenTheOperator_WhenMarkingANotificationRead_ThenItIsRefused() {
    assertThatThrownBy(
            () -> service.markRead(new Operator(UserId.newId()), ACME, NotificationId.newId()))
        .isInstanceOf(NotATenantMemberException.class);
  }
}
