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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

  /**
   * Every event a caller may raise comes back out of the inbox as that event: the two enums are
   * translated into one another (ADR-008), and a translation that crossed two of them would tell
   * somebody the opposite of what happened.
   */
  @ParameterizedTest
  @EnumSource(NotifiableEvent.class)
  void GivenAnyEvent_WhenSomebodyIsNotifiedOfIt_ThenTheirInboxCarriesThatEvent(
      NotifiableEvent event) {
    service.notify(themself(ACME, GRACE), new NotifyCommand(ACME, ADA, event, UUID.randomUUID()));

    assertThat(service.findMine(themself(ACME, ADA), ACME))
        .singleElement()
        .extracting(PublishedNotification::kind)
        .isEqualTo(event);
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
    assertThat(service.findMine(themself(ACME, ADA), ACME)).isEmpty();
    assertThat(audit.changes()).isEmpty();
  }

  /**
   * The platform operator belongs to no organisation, so has nobody to tell anything to (ADR-030).
   * Refused before anything is stored, and nothing recorded.
   */
  @Test
  void GivenTheOperator_WhenNotifyingSomebody_ThenItIsRefusedAndNothingIsStored() {
    assertThatThrownBy(
            () ->
                service.notify(
                    new Operator(UserId.newId()),
                    new NotifyCommand(
                        ACME, ADA, NotifiableEvent.SWAP_REQUESTED, UUID.randomUUID())))
        .isInstanceOf(NotATenantMemberException.class);
    assertThat(service.findMine(themself(ACME, ADA), ACME)).isEmpty();
    assertThat(audit.changes()).isEmpty();
  }

  /**
   * The actor is linked to Ada's own record, so the inbox lookup alone would find her notification:
   * only the check of the actor against the organisation named refuses. Over HTTP the organisation
   * comes from the token, so only the port can be handed a mismatched one.
   */
  @Test
  void
      GivenAnActorOfAnotherOrganisation_WhenAcknowledgingOneOfOurs_ThenItIsRefusedAndStaysUnread() {
    NotificationId adas = notifyAda(UUID.randomUUID());

    assertThatThrownBy(() -> service.markRead(themself(RIVAL, ADA), ACME, adas))
        .isInstanceOf(CrossTenantAccessException.class);
    assertThat(service.findMine(themself(ACME, ADA), ACME))
        .singleElement()
        .extracting(PublishedNotification::read)
        .isEqualTo(false);
    assertThat(audit.of(NotificationChange.NOTIFICATION_READ)).isEmpty();
  }

  /** Nobody without a personnel record has an inbox to acknowledge anything in. */
  @Test
  void GivenAnActorWithNoPersonnelRecord_WhenAcknowledging_ThenItIsRefusedAndNothingIsRecorded() {
    NotificationId adas = notifyAda(UUID.randomUUID());

    assertThatThrownBy(
            () -> service.markRead(new TenantActor(UserId.newId(), ACME, Role.ADMIN), ACME, adas))
        .isInstanceOf(NotAddressableException.class);
    assertThat(service.findMine(themself(ACME, ADA), ACME))
        .singleElement()
        .extracting(PublishedNotification::read)
        .isEqualTo(false);
    assertThat(audit.of(NotificationChange.NOTIFICATION_READ)).isEmpty();
  }

  /**
   * F16 opens an inbox to any member linked to a personnel record, whatever their role: a Scheduler
   * who also works shifts is told about a swap as anybody else is.
   */
  @Test
  void GivenASchedulerLinkedToTheirRecord_WhenReadingTheirInbox_ThenTheirNotificationsAreThere() {
    NotificationId raised = notifyAda(UUID.randomUUID());
    TenantActor adaAsScheduler =
        new TenantActor(UserId.newId(), ACME, Role.SCHEDULER, Optional.of(ADA));

    assertThat(service.findMine(adaAsScheduler, ACME))
        .extracting(PublishedNotification::id)
        .containsExactly(raised);
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

  // --- #273, finding 10: the double refuses each thing the adapter refuses, one at a time -----

  private static Notification adasRequest() {
    return new Notification(
        NotificationId.newId(),
        ACME,
        ADA,
        NotificationKind.SWAP_REQUESTED,
        UUID.randomUUID(),
        NOON);
  }

  /** The organisation half of the guard alone: the recipient is the same person's identity. */
  @Test
  void
      GivenAnIdentityHeldByAnotherOrganisationForTheSameRecipient_WhenTheDoubleSavesOverIt_ThenItIsRefused() {
    Notification adas = adasRequest();
    notifications.save(adas);
    Notification movedToRival =
        new Notification(adas.id(), RIVAL, ADA, adas.kind(), adas.subject(), adas.occurredAt());

    assertThatThrownBy(() -> notifications.save(movedToRival))
        .isInstanceOf(IllegalStateException.class);
    assertThat(notifications.findFor(ACME, ADA)).containsExactly(adas);
    assertThat(notifications.findFor(RIVAL, ADA)).isEmpty();
  }

  /** The recipient half of the guard alone, as {@code JdbcNotificationRepository} refuses it. */
  @Test
  void
      GivenAnIdentityHeldForAColleagueInTheSameOrganisation_WhenTheDoubleSavesOverIt_ThenItIsRefused() {
    Notification adas = adasRequest();
    notifications.save(adas);
    Notification movedToGrace =
        new Notification(adas.id(), ACME, GRACE, adas.kind(), adas.subject(), adas.occurredAt());

    assertThatThrownBy(() -> notifications.save(movedToGrace))
        .isInstanceOf(IllegalStateException.class);
    assertThat(notifications.findFor(ACME, ADA)).containsExactly(adas);
    assertThat(notifications.findFor(ACME, GRACE)).isEmpty();
  }

  /** The adapter only ever sets {@code read}; an unread copy saved later is stale, not a change. */
  @Test
  void GivenAReadNotification_WhenTheDoubleSavesAnUnreadCopy_ThenItIsStillRead() {
    Notification unread = adasRequest();
    notifications.save(unread);
    notifications.save(unread.markedRead());

    notifications.save(unread);

    assertThat(notifications.findFor(ACME, ADA))
        .singleElement()
        .extracting(Notification::isRead)
        .isEqualTo(true);
  }

  /** What somebody was told is never rewritten, as finding 3 holds the adapter to. */
  @Test
  void GivenAnotherKind_WhenTheDoubleSavesOverANotification_ThenItIsRefusedAndUnchanged() {
    Notification adas = adasRequest();
    notifications.save(adas);

    assertDoubleRefusesAndKeeps(
        adas,
        new Notification(
            adas.id(),
            ACME,
            ADA,
            NotificationKind.SWAP_APPROVED,
            adas.subject(),
            adas.occurredAt()));
  }

  @Test
  void GivenAnotherSubject_WhenTheDoubleSavesOverANotification_ThenItIsRefusedAndUnchanged() {
    Notification adas = adasRequest();
    notifications.save(adas);

    assertDoubleRefusesAndKeeps(
        adas,
        new Notification(adas.id(), ACME, ADA, adas.kind(), UUID.randomUUID(), adas.occurredAt()));
  }

  @Test
  void GivenAnotherMoment_WhenTheDoubleSavesOverANotification_ThenItIsRefusedAndUnchanged() {
    Notification adas = adasRequest();
    notifications.save(adas);

    assertDoubleRefusesAndKeeps(
        adas,
        new Notification(
            adas.id(), ACME, ADA, adas.kind(), adas.subject(), NOON.plusSeconds(3600)));
  }

  /** Equality is by identity alone, so each column is compared. */
  private void assertDoubleRefusesAndKeeps(Notification stored, Notification changed) {
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

  /**
   * Hand-written, like every other double here: the repository is our own port. It copies the real
   * adapter's refusals, because a double accepting what the database refuses tests nothing.
   */
  private static final class StubNotificationRepository implements NotificationRepository {

    private final Map<NotificationId, Notification> saved = new HashMap<>();

    /**
     * Keeps a held notification read when an unread copy is saved, as {@code
     * JdbcNotificationRepository}'s upsert only ever sets {@code read}.
     *
     * @throws IllegalStateException if the identity already belongs to another organisation or
     *     recipient, or records another kind, subject or moment, as that upsert refuses it
     */
    @Override
    public void save(Notification notification) {
      Notification held = saved.get(notification.id());
      if (held == null) {
        saved.put(notification.id(), notification);
        return;
      }
      if (!(held.organisation().equals(notification.organisation())
          && held.recipient().equals(notification.recipient())
          && held.kind() == notification.kind()
          && held.subject().equals(notification.subject())
          && held.occurredAt().equals(notification.occurredAt()))) {
        throw new IllegalStateException(
            ("Refused to store %s: that identity already belongs to another organisation or"
                    + " recipient, or records another kind, subject or moment")
                .formatted(notification.id().value()));
      }
      saved.put(notification.id(), held.isRead() ? notification.markedRead() : notification);
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
