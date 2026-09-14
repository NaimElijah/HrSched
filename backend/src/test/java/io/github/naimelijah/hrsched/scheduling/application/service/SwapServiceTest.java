package io.github.naimelijah.hrsched.scheduling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.application.port.in.AssignToShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.DefineShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PublishedSwapRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RequestSwapCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseConstraintsCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.out.Notifier;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SchedulingChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeNotQualifiedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeUnavailableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.OverlappingAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapWithThemselfException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssigneeException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownSwapRequestException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Specification of the swap use cases, driven without Spring.
 *
 * <p><strong>The publication path here is real.</strong> The service under test is wired to an
 * actual {@code SchedulingService} over the same fakes, not to a stub of it, because the claim
 * worth testing is that approving a swap goes through the same publication every other roster goes
 * through — constraint check, supersession and all. A stubbed {@code PlanRostersUseCase} would
 * prove only that this class calls something.
 *
 * <p>So the test that matters most is the one where the swap would break a working-time rule: it is
 * refused, the request stays pending, and the roster people are already working to is untouched.
 *
 * <p>Every constraint value below is invented to make a specific case pass or fail. None of them is
 * taken from any jurisdiction.
 */
class SwapServiceTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final TimeRange MONDAY_DAY =
      new TimeRange(Instant.parse("2026-10-05T08:00:00Z"), Instant.parse("2026-10-05T16:00:00Z"));
  private static final TimeRange THE_WEEK =
      new TimeRange(Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z"));

  /** Fixed, so the time a request records is something these tests state rather than tolerate. */
  private static final Instant RAISED_AT = Instant.parse("2026-10-01T09:30:00Z");

  private FakeShifts shifts;
  private FakeRosters rosters;
  private FakeConstraintSets constraintSets;
  private FakeWorkforce workforce;
  private FakeSwaps swaps;
  private RecordingNotifier notifier;
  private RecordingAuditTrail audit;
  private SchedulingService scheduling;
  private SwapService service;

  private EmployeeId ada;
  private EmployeeId grace;

  @BeforeEach
  void setUp() {
    shifts = new FakeShifts();
    rosters = new FakeRosters();
    constraintSets = new FakeConstraintSets();
    workforce = new FakeWorkforce();
    swaps = new FakeSwaps();
    notifier = new RecordingNotifier();
    audit = new RecordingAuditTrail();
    scheduling =
        new SchedulingService(
            shifts, rosters, constraintSets, workforce, new AssignsNobody(), audit);
    service =
        new SwapService(
            swaps,
            rosters,
            shifts,
            workforce,
            scheduling,
            notifier,
            audit,
            Clock.fixed(RAISED_AT, ZoneOffset.UTC));
    ada = available(ACME, "Ada", Set.of());
    grace = available(ACME, "Grace", Set.of());
  }

  private static TenantActor scheduler(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.SCHEDULER);
  }

  private static TenantActor admin(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.ADMIN);
  }

  private static TenantActor themself(OrganisationId organisation, EmployeeId employee) {
    return new TenantActor(UserId.newId(), organisation, Role.EMPLOYEE, Optional.of(employee));
  }

  private EmployeeId available(
      OrganisationId organisation, String name, Set<QualificationId> holds) {
    EmployeeId id = EmployeeId.newId();
    workforce.add(organisation, new RosterCandidate(id, name, holds, List.of()));
    return id;
  }

  private ShiftId aShift(Set<QualificationId> required) {
    return scheduling.defineShift(
        scheduler(ACME), new DefineShiftCommand(ACME, MONDAY_DAY, 1, required));
  }

  /**
   * A published roster with exactly one assignment on it, built deterministically: generation runs
   * before the shift exists, so nothing is auto-filled and the roster carries only what is put on
   * it.
   */
  private Roster publishedWith(EmployeeId who, ShiftId shift) {
    Roster draft =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    scheduling.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, who));
    return scheduling.publish(scheduler(ACME), ACME, draft.id());
  }

  @Test
  void GivenTheirOwnShift_WhenAnEmployeeAsksForASwap_ThenItIsRecordedAsRequested() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);

    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, published.id(), shift, ada, grace));

    assertThat(service.findSwapRequests(scheduler(ACME), ACME))
        .singleElement()
        .satisfies(
            published2 -> {
              assertThat(published2.id()).isEqualTo(request);
              assertThat(published2.status()).isEqualTo(SwapRequestStatus.REQUESTED);
              assertThat(published2.requester()).isEqualTo(ada);
              assertThat(published2.counterparty()).isEqualTo(grace);
            });
  }

  /**
   * The time is what a Scheduler's queue is ordered by, so it has to be the clock's rather than
   * whatever order the store happened to keep (ADR-004).
   */
  @Test
  void GivenASwapIsRaised_WhenItIsStored_ThenItCarriesTheTimeFromTheClock() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);

    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, published.id(), shift, ada, grace));

    assertThat(swaps.byId(request).requestedAt()).isEqualTo(RAISED_AT);
  }

  @Test
  void GivenAPendingSwap_WhenASchedulerDecidesIt_ThenTheTimeItWasRaisedIsUnchanged() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, published.id(), shift, ada, grace));

    service.rejectSwap(scheduler(ACME), ACME, request);

    assertThat(swaps.byId(request).requestedAt()).isEqualTo(RAISED_AT);
  }

  /** Q11: the colleague is informed rather than asked, and being informed is a real thing here. */
  @Test
  void GivenASwapIsRequested_WhenItIsRaised_ThenTheColleagueIsTold() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);

    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, published.id(), shift, ada, grace));

    assertThat(notifier.told).containsExactly(new Told("REQUESTED", grace, request));
  }

  @Test
  void GivenAColleaguesShift_WhenAnEmployeeAsksToSwapIt_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);

    assertThatThrownBy(
            () ->
                service.requestSwap(
                    themself(ACME, grace),
                    new RequestSwapCommand(ACME, published.id(), shift, ada, grace)))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnAdmin_WhenAskingOnSomebodyElsesBehalf_ThenItIsPermitted() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);

    assertThatCode(
            () ->
                service.requestSwap(
                    admin(ACME), new RequestSwapCommand(ACME, published.id(), shift, ada, grace)))
        .doesNotThrowAnyException();
  }

  @Test
  void GivenAShiftTheyDoNotCover_WhenAskingForASwap_ThenItIsRefused() {
    ShiftId theirs = aShift(Set.of());
    ShiftId other = aShift(Set.of());
    Roster published = publishedWith(ada, theirs);

    assertThatThrownBy(
            () ->
                service.requestSwap(
                    themself(ACME, ada),
                    new RequestSwapCommand(ACME, published.id(), other, ada, grace)))
        .isInstanceOf(UnknownAssignmentException.class);
  }

  /** A draft binds nobody, so there is nothing on it to hand over. */
  @Test
  void GivenADraftRoster_WhenAskingForASwapAgainstIt_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster draft =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));

    assertThatThrownBy(
            () ->
                service.requestSwap(
                    themself(ACME, ada),
                    new RequestSwapCommand(ACME, draft.id(), shift, ada, grace)))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenThemselfAsTheColleague_WhenAskingForASwap_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);

    assertThatThrownBy(
            () ->
                service.requestSwap(
                    themself(ACME, ada),
                    new RequestSwapCommand(ACME, published.id(), shift, ada, ada)))
        .isInstanceOf(SwapWithThemselfException.class);
  }

  /**
   * Eligibility is decided when a Scheduler answers, not when an Employee asks - asking would mean
   * reading the workforce directory, which an Employee may not do. So naming somebody impossible is
   * accepted as a request and refused as a decision.
   */
  @Test
  void GivenAColleagueFromAnotherOrganisation_WhenApprovingTheSwap_ThenItIsRefused() {
    EmployeeId stranger = available(RIVAL, "Their Employee", Set.of());
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada),
            new RequestSwapCommand(ACME, published.id(), shift, ada, stranger));

    assertThatThrownBy(() -> service.approveSwap(scheduler(ACME), ACME, request))
        .isInstanceOf(UnknownAssigneeException.class);
  }

  /** R4, decided where it can be: at approval, by somebody who may read who holds what. */
  @Test
  void GivenAColleagueWhoLacksTheQualification_WhenApprovingTheSwap_ThenItIsRefused() {
    QualificationId forklift = QualificationId.newId();
    workforce.define(ACME, forklift);
    EmployeeId qualifiedAda = available(ACME, "Ada", Set.of(forklift));
    ShiftId shift = aShift(Set.of(forklift));
    Roster published = publishedWith(qualifiedAda, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, qualifiedAda),
            new RequestSwapCommand(ACME, published.id(), shift, qualifiedAda, grace));

    assertThatThrownBy(() -> service.approveSwap(scheduler(ACME), ACME, request))
        .isInstanceOf(AssigneeNotQualifiedException.class);
  }

  /**
   * The other half of that: an Employee may raise the request at all. Before eligibility moved to
   * approval this failed with a role refusal, because checking it meant listing colleagues.
   */
  @Test
  void GivenAnEmployee_WhenAskingForASwap_ThenTheyAreNotRefusedForReadingColleagues() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);

    assertThatCode(
            () ->
                service.requestSwap(
                    themself(ACME, ada),
                    new RequestSwapCommand(ACME, published.id(), shift, ada, grace)))
        .doesNotThrowAnyException();
  }

  @Test
  void GivenAPendingSwap_WhenASchedulerApprovesIt_ThenAReplacementRosterCarriesTheColleague() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));

    Roster replacement = service.approveSwap(scheduler(ACME), ACME, request);

    assertThat(replacement.status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(replacement.assignments()).containsExactly(new Assignment(shift, grace));
    assertThat(replacement.id()).isNotEqualTo(original.id());
  }

  /** R6: the roster people are working to is superseded, never edited. */
  @Test
  void GivenAnApprovedSwap_WhenTheReplacementIsPublished_ThenTheOriginalIsSuperseded() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));

    service.approveSwap(scheduler(ACME), ACME, request);

    assertThat(rosters.byId(original.id()).status()).isEqualTo(RosterStatus.SUPERSEDED);
    assertThat(rosters.byId(original.id()).assignments())
        .describedAs("the superseded roster is a historical fact and is not rewritten")
        .containsExactly(new Assignment(shift, ada));
  }

  @Test
  void GivenAnApprovedSwap_WhenItIsDecided_ThenBothPartiesAreTold() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    notifier.told.clear();

    service.approveSwap(scheduler(ACME), ACME, request);

    assertThat(notifier.told)
        .containsExactlyInAnyOrder(
            new Told("APPROVED", grace, request), new Told("APPROVED", ada, request));
  }

  @Test
  void GivenAnApprovedSwap_WhenItIsDecided_ThenTheDecisionAndThePublicationAreBothRecorded() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    audit.forget();

    service.approveSwap(scheduler(ACME), ACME, request);

    assertThat(audit.changes())
        .describedAs("the swap says why, and the roster entries say what changed")
        .contains(
            SchedulingChange.SWAP_APPROVED,
            SchedulingChange.ROSTER_PUBLISHED,
            SchedulingChange.ROSTER_SUPERSEDED);
  }

  /**
   * The guarantee the whole design turns on. Approving a swap publishes, and publication refuses a
   * roster that breaks a rule — so a swap cannot become a way around the rules that generation
   * cannot get around either. There is no override here because there is none there.
   */
  @Test
  void GivenASwapThatWouldBreakADailyLimit_WhenApproving_ThenItIsRefusedAndNothingChanges() {
    ShiftId monday = aShift(Set.of());
    // Ends as the day shift starts, so covering both is sixteen hours of work rather than two
    // places at once, which would be refused for a different reason (R3).
    TimeRange earlyMonday =
        new TimeRange(Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-05T08:00:00Z"));
    ShiftId early =
        scheduling.defineShift(
            scheduler(ACME), new DefineShiftCommand(ACME, earlyMonday, 1, Set.of()));
    Roster draft =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    scheduling.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), monday, ada));
    scheduling.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), early, grace));
    Roster original = scheduling.publish(scheduler(ACME), ACME, draft.id());
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), monday, ada, grace));
    // Invented: ten hours a day, which Grace covering both eight-hour shifts cannot satisfy.
    scheduling.reviseConstraints(
        admin(ACME),
        new ReviseConstraintsCommand(
            ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(10)))));

    assertThatThrownBy(() -> service.approveSwap(scheduler(ACME), ACME, request))
        .isInstanceOf(RosterNotCompliantException.class);

    assertThat(rosters.byId(original.id()).status())
        .describedAs("the roster people are already working to is untouched")
        .isEqualTo(RosterStatus.PUBLISHED);
    assertThat(swaps.byId(request).status())
        .describedAs("a refused swap is still waiting on somebody, not silently decided")
        .isEqualTo(SwapRequestStatus.REQUESTED);
    assertThat(audit.changes()).doesNotContain(SchedulingChange.SWAP_APPROVED);
  }

  @Test
  void GivenAnAlreadyApprovedSwap_WhenApprovingItAgain_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    service.approveSwap(scheduler(ACME), ACME, request);

    assertThatThrownBy(() -> service.approveSwap(scheduler(ACME), ACME, request))
        .isInstanceOf(SwapNotPendingException.class);
  }

  @Test
  void GivenAnEmployeeRole_WhenApprovingASwap_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));

    assertThatThrownBy(() -> service.approveSwap(themself(ACME, grace), ACME, request))
        .isInstanceOf(NotPermittedException.class);
  }

  /**
   * An Admin manages people and rules; only a Scheduler builds rosters, and approving builds one.
   */
  @Test
  void GivenAnAdminRole_WhenApprovingASwap_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));

    assertThatThrownBy(() -> service.approveSwap(admin(ACME), ACME, request))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAPendingSwap_WhenRejected_ThenItIsRecordedAndNoRosterChanges() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));

    service.rejectSwap(scheduler(ACME), ACME, request);

    assertThat(swaps.byId(request).status()).isEqualTo(SwapRequestStatus.REJECTED);
    assertThat(rosters.byId(original.id()).status())
        .describedAs("rejecting decides a request and changes no roster at all")
        .isEqualTo(RosterStatus.PUBLISHED);
    assertThat(audit.of(SchedulingChange.SWAP_REJECTED)).hasSize(1);
  }

  /** The same hole as manual assignment, on the path that publishes without anybody re-checking. */
  @Test
  void GivenAColleagueWhoCannotWorkThen_WhenApprovingTheSwap_ThenItIsRefused() {
    EmployeeId busy = EmployeeId.newId();
    workforce.add(ACME, new RosterCandidate(busy, "Busy", Set.of(), List.of(MONDAY_DAY)));
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, published.id(), shift, ada, busy));

    assertThatThrownBy(() -> service.approveSwap(scheduler(ACME), ACME, request))
        .isInstanceOf(AssigneeUnavailableException.class);
  }

  /**
   * R3 at approval, with no rules configured. Grace already works an afternoon overlapping Ada's
   * day shift, so handing it to her would put her in two places at once, and an empty constraint
   * set checks nothing. Publication refuses that for itself, and the approval is refused with it.
   */
  @Test
  void GivenAColleagueAlreadyWorkingThen_WhenApprovingTheSwap_ThenItIsRefusedAndNothingChanges() {
    ShiftId day = aShift(Set.of());
    TimeRange afternoon =
        new TimeRange(Instant.parse("2026-10-05T12:00:00Z"), Instant.parse("2026-10-05T20:00:00Z"));
    ShiftId gracesAfternoon =
        scheduling.defineShift(
            scheduler(ACME), new DefineShiftCommand(ACME, afternoon, 1, Set.of()));
    Roster draft =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    scheduling.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), day, ada));
    scheduling.assign(
        scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), gracesAfternoon, grace));
    Roster original = scheduling.publish(scheduler(ACME), ACME, draft.id());
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), day, ada, grace));

    assertThatThrownBy(() -> service.approveSwap(scheduler(ACME), ACME, request))
        .isInstanceOf(OverlappingAssignmentException.class);

    assertThat(rosters.byId(original.id()).status())
        .describedAs("the roster people are already working to is untouched")
        .isEqualTo(RosterStatus.PUBLISHED);
    assertThat(swaps.byId(request).status())
        .describedAs("a refused swap is still waiting on somebody, not silently decided")
        .isEqualTo(SwapRequestStatus.REQUESTED);
    assertThat(audit.changes()).doesNotContain(SchedulingChange.SWAP_APPROVED);
  }

  /**
   * An approval that waited for another has to decide from what that one committed, so the lock
   * comes before the request's roster is read - not only before the publication it ends in
   * (ADR-027).
   */
  @Test
  void GivenAPendingSwap_WhenApproving_ThenTheOrganisationsPublicationsWaitBeforeTheRosterIsRead() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    rosters.forgetCalls();

    service.approveSwap(scheduler(ACME), ACME, request);

    assertThat(rosters.calls()).first().isEqualTo(FakeRosters.Call.LOCK_PUBLICATIONS);
  }

  /**
   * The colleague was told they were being asked; telling only the requester when it is refused
   * leaves them holding an open question nobody ever answers.
   */
  @Test
  void GivenARejectedSwap_WhenTheDecisionIsMade_ThenBothPartiesAreTold() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, published.id(), shift, ada, grace));
    notifier.told.clear();

    service.rejectSwap(scheduler(ACME), ACME, request);

    assertThat(notifier.told)
        .containsExactlyInAnyOrder(
            new Told("REJECTED", ada, request), new Told("REJECTED", grace, request));
  }

  @Test
  void GivenAnAlreadyRejectedSwap_WhenRejectingItAgain_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    service.rejectSwap(scheduler(ACME), ACME, request);

    assertThatThrownBy(() -> service.rejectSwap(scheduler(ACME), ACME, request))
        .isInstanceOf(SwapNotPendingException.class);
  }

  @Test
  void GivenNoSuchSwap_WhenDecidingIt_ThenItIsRefused() {
    assertThatThrownBy(() -> service.approveSwap(scheduler(ACME), ACME, SwapRequestId.newId()))
        .isInstanceOf(UnknownSwapRequestException.class);
  }

  @Test
  void GivenASchedulerOfAnotherOrganisation_WhenListingOurSwaps_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findSwapRequests(scheduler(RIVAL), ACME))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  /**
   * A swap request names two colleagues and says what one of them wants off their plate, so it is
   * personal data in the same way a roster is.
   */
  @Test
  void GivenSwapsBetweenOtherPeople_WhenAnEmployeeLists_ThenTheyOnlySeeTheirOwn() {
    EmployeeId hopper = available(ACME, "Hopper", Set.of());
    ShiftId mine = aShift(Set.of());
    ShiftId theirs = aShift(Set.of());
    Roster draft =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    scheduling.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), mine, ada));
    scheduling.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), theirs, hopper));
    Roster published = scheduling.publish(scheduler(ACME), ACME, draft.id());
    SwapRequestId ours =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, published.id(), mine, ada, grace));
    service.requestSwap(
        themself(ACME, hopper),
        new RequestSwapCommand(ACME, published.id(), theirs, hopper, grace));

    assertThat(service.findSwapRequests(themself(ACME, ada), ACME))
        .extracting(PublishedSwapRequest::id)
        .containsExactly(ours);
    assertThat(service.findSwapRequests(scheduler(ACME), ACME))
        .describedAs("a Scheduler has to see every request, because answering them is their job")
        .hasSize(2);
  }

  @Test
  void GivenTheCounterparty_WhenReadingTheSwapTheyWereAskedAbout_ThenItIsReturned() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, published.id(), shift, ada, grace));

    assertThat(service.findSwapRequest(themself(ACME, grace), ACME, request))
        .map(PublishedSwapRequest::requester)
        .contains(ada);
  }

  /** Reading one must not reveal what the listing withholds. */
  @Test
  void GivenAnEmployeeNotPartyToTheSwap_WhenReadingIt_ThenItIsAbsent() {
    EmployeeId hopper = available(ACME, "Hopper", Set.of());
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, published.id(), shift, ada, grace));

    assertThat(service.findSwapRequest(themself(ACME, hopper), ACME, request)).isEmpty();
  }

  @Test
  void GivenASchedulerOfAnotherOrganisation_WhenReadingOurSwap_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster published = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, published.id(), shift, ada, grace));

    assertThatThrownBy(() -> service.findSwapRequest(scheduler(RIVAL), ACME, request))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  /**
   * What the notifier was told, so that "the colleague is informed" is something a test can ask.
   */
  private record Told(String what, EmployeeId who, SwapRequestId request) {}

  /**
   * A generator that fills nothing, so a roster under test carries only what the test put on it.
   *
   * <p>Deliberately not FirstFitGenerator: this class is about deciding swaps, and a draft that
   * arrived pre-filled would make every setup depend on the order shifts happened to be defined in,
   * which has nothing to do with what is being specified.
   */
  private static final class AssignsNobody implements RosterGenerator {
    @Override
    public List<Assignment> generate(
        List<Shift> toCover, List<RosterCandidate> candidates, ConstraintSet constraints) {
      return List.of();
    }
  }

  private static final class RecordingNotifier implements Notifier {

    private final List<Told> told = new ArrayList<>();

    @Override
    public void swapRequested(
        Actor actor, OrganisationId organisation, EmployeeId counterparty, SwapRequestId request) {
      told.add(new Told("REQUESTED", counterparty, request));
    }

    @Override
    public void swapApproved(
        Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
      told.add(new Told("APPROVED", party, request));
    }

    @Override
    public void swapRejected(
        Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
      told.add(new Told("REJECTED", party, request));
    }
  }

  /** Hand-written, like every other double here: the repository is our own port. */
  private static final class FakeSwaps implements SwapRequestRepository {

    private final Map<SwapRequestId, SwapRequest> stored = new LinkedHashMap<>();

    @Override
    public void save(SwapRequest request) {
      stored.put(request.id(), request);
    }

    @Override
    public Optional<SwapRequest> findById(OrganisationId organisation, SwapRequestId id) {
      return Optional.ofNullable(stored.get(id))
          .filter(request -> request.organisation().equals(organisation));
    }

    @Override
    public List<SwapRequest> findByOrganisation(OrganisationId organisation) {
      return stored.values().stream()
          .filter(request -> request.organisation().equals(organisation))
          .toList();
    }

    SwapRequest byId(SwapRequestId id) {
      return stored.get(id);
    }
  }
}
