package io.github.naimelijah.hrsched.scheduling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.naimelijah.hrsched.scheduling.application.port.in.AssignToShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.DefineShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PublishedSwapRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RequestSwapCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseConstraintsCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SchedulingChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.application.service.RecordingNotifier.Told;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeNotQualifiedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeUnavailableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapAlreadyRequestedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapWithThemselfException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssigneeException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownSwapRequestException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
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
import java.util.List;
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
  private static final TimeRange TUESDAY_DAY =
      new TimeRange(Instant.parse("2026-10-06T08:00:00Z"), Instant.parse("2026-10-06T16:00:00Z"));
  private static final TimeRange NEXT_WEEK =
      new TimeRange(Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-19T00:00:00Z"));
  private static final TimeRange NEXT_MONDAY_DAY =
      new TimeRange(Instant.parse("2026-10-12T08:00:00Z"), Instant.parse("2026-10-12T16:00:00Z"));

  /** Fixed, so the time a request records is something these tests state rather than tolerate. */
  private static final Instant RAISED_AT = Instant.parse("2026-10-01T09:30:00Z");

  private CallLog log;
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
    log = new CallLog();
    shifts = new FakeShifts();
    rosters = new FakeRosters(log);
    constraintSets = new FakeConstraintSets();
    workforce = new FakeWorkforce();
    swaps = new FakeSwaps(log);
    notifier = new RecordingNotifier();
    audit = new RecordingAuditTrail();
    scheduling =
        new SchedulingService(
            shifts,
            rosters,
            constraintSets,
            swaps,
            workforce,
            new AssignsNobody(),
            notifier,
            audit,
            Clock.fixed(RAISED_AT, ZoneOffset.UTC));
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
    workforce.add(
        organisation, new RosterCandidate(id, name, holds, List.of(), StatedPreferences.none()));
    return id;
  }

  private ShiftId aShift(Set<QualificationId> required) {
    return scheduling.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, required, false));
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
            scheduler(ACME),
            new DefineShiftCommand(ACME, earlyMonday, ZoneOffset.UTC, 1, Set.of(), false));
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
    workforce.add(
        ACME,
        new RosterCandidate(busy, "Busy", Set.of(), List.of(MONDAY_DAY), StatedPreferences.none()));
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
            scheduler(ACME),
            new DefineShiftCommand(ACME, afternoon, ZoneOffset.UTC, 1, Set.of(), false));
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
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations())
                    .extracting(ConstraintViolation::employee, ConstraintViolation::constraint)
                    .containsExactly(tuple(grace, ConstraintViolation.OVERLAPPING_WORK)));

    assertThat(rosters.byId(original.id()).status())
        .describedAs("the roster people are already working to is untouched")
        .isEqualTo(RosterStatus.PUBLISHED);
    assertThat(swaps.byId(request).status())
        .describedAs("a refused swap is still waiting on somebody, not silently decided")
        .isEqualTo(SwapRequestStatus.REQUESTED);
    assertThat(audit.changes()).doesNotContain(SchedulingChange.SWAP_APPROVED);
  }

  /**
   * R35 at approval. Ada's Sunday night runs into next week, where Grace already works an early
   * Monday on the next roster. Handing her Ada's night would put her in two places at once across
   * the two rosters, which publication refuses whichever roster each shift is on.
   */
  @Test
  void
      GivenAColleagueWorkingThenOnTheAdjacentRoster_WhenApprovingTheSwap_ThenItIsRefusedAndNothingChanges() {
    TimeRange sundayNight =
        new TimeRange(Instant.parse("2026-10-11T22:00:00Z"), Instant.parse("2026-10-12T06:00:00Z"));
    TimeRange nextWeek =
        new TimeRange(Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-19T00:00:00Z"));
    TimeRange nextMondayEarly =
        new TimeRange(Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-12T08:00:00Z"));
    ShiftId adasNight =
        scheduling.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, sundayNight, ZoneOffset.UTC, 1, Set.of(), false));
    Roster original = publishedWith(ada, adasNight);
    Roster nextDraft =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, nextWeek));
    ShiftId gracesEarly =
        scheduling.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, nextMondayEarly, ZoneOffset.UTC, 1, Set.of(), false));
    scheduling.assign(
        scheduler(ACME), new AssignToShiftCommand(ACME, nextDraft.id(), gracesEarly, grace));
    scheduling.publish(scheduler(ACME), ACME, nextDraft.id());
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada),
            new RequestSwapCommand(ACME, original.id(), adasNight, ada, grace));

    assertThatThrownBy(() -> service.approveSwap(scheduler(ACME), ACME, request))
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations())
                    .singleElement()
                    .satisfies(
                        overlap -> {
                          assertThat(overlap.employee()).isEqualTo(grace);
                          assertThat(overlap.constraint())
                              .isEqualTo(ConstraintViolation.OVERLAPPING_WORK);
                        }));

    assertThat(rosters.byId(original.id()).status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(swaps.byId(request).status()).isEqualTo(SwapRequestStatus.REQUESTED);
    assertThat(audit.changes()).doesNotContain(SchedulingChange.SWAP_APPROVED);
  }

  /**
   * The consequence the owner accepted with #205. A swap is approved by publishing a replacement
   * carrying everybody else's work unchanged (ADR-022), and publication judges every rule. Grace
   * has since declared she cannot work the shift she holds on this roster, so the replacement would
   * carry her through it, and the swap between two other people is refused until a Scheduler
   * reassigns her shift - as it already is when the rules have been tightened.
   */
  @Test
  void
      GivenSomebodyElseOnTheRosterWhoHasSinceDeclaredAbsence_WhenApprovingASwap_ThenItIsRefusedAndNothingChanges() {
    EmployeeId linus = available(ACME, "Linus", Set.of());
    ShiftId adasDay = aShift(Set.of());
    TimeRange tuesday =
        new TimeRange(Instant.parse("2026-10-06T08:00:00Z"), Instant.parse("2026-10-06T16:00:00Z"));
    ShiftId gracesTuesday =
        scheduling.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, tuesday, ZoneOffset.UTC, 1, Set.of(), false));
    Roster draft =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    scheduling.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), adasDay, ada));
    scheduling.assign(
        scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), gracesTuesday, grace));
    Roster original = scheduling.publish(scheduler(ACME), ACME, draft.id());
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), adasDay, ada, linus));
    workforce.declareUnavailable(ACME, grace, tuesday);

    assertThatThrownBy(() -> service.approveSwap(scheduler(ACME), ACME, request))
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations())
                    .containsExactly(
                        ConstraintViolation.declaredUnavailable(grace, gracesTuesday)));

    assertThat(rosters.byId(original.id()).status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(swaps.byId(request).status()).isEqualTo(SwapRequestStatus.REQUESTED);
    assertThat(audit.changes()).doesNotContain(SchedulingChange.SWAP_APPROVED);
  }

  /**
   * An approval that waited for another has to decide from what that one committed, so the lock
   * comes before the request is read, as well as its roster - not only before the publication it
   * ends in (ADR-027). Read first, a request the other approval has just decided still looks
   * pending, and the refusal names a superseded roster instead of saying it was already decided.
   */
  @Test
  void
      GivenAPendingSwap_WhenApproving_ThenTheOrganisationsPublicationsWaitBeforeTheRequestIsRead() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    log.forget();

    service.approveSwap(scheduler(ACME), ACME, request);

    assertThat(log.calls()).first().isEqualTo(CallLog.Call.LOCK_PUBLICATIONS);
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

  // --- R33: a pending request lapses when the roster it names is superseded ------------------

  /** Ada asks Grace to take her shift on a roster that is then replaced before anybody decides. */
  private SwapRequestId pendingOnARosterThenReplaced() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    notifier.told.clear();
    audit.forget();
    publishedWith(ada, shift);
    return request;
  }

  /**
   * A request names the roster it was raised against. Once a newer roster replaces that one, there
   * is nothing left for a Scheduler to approve, so the publication that replaced it lapses it.
   */
  @Test
  void GivenAPendingSwap_WhenItsRosterIsSuperseded_ThenItLapses() {
    SwapRequestId request = pendingOnARosterThenReplaced();

    assertThat(swaps.byId(request).status()).isEqualTo(SwapRequestStatus.LAPSED);
  }

  /** Both parties were waiting on an answer, and a lapse is one; R33 says they are told. */
  @Test
  void GivenAPendingSwap_WhenItsRosterIsSuperseded_ThenBothPartiesAreToldItLapsed() {
    SwapRequestId request = pendingOnARosterThenReplaced();

    assertThat(notifier.told)
        .containsExactlyInAnyOrder(
            new Told("LAPSED", ada, request), new Told("LAPSED", grace, request));
  }

  /**
   * Recorded as its own change, because nobody asked for it, and after the publication is stored:
   * an audit line outlives the rollback of a save that fails after it (ADR-023).
   */
  @Test
  void GivenAPendingSwap_WhenItLapses_ThenTheLapseIsRecordedAfterThePublication() {
    pendingOnARosterThenReplaced();

    assertThat(audit.changes())
        .containsSubsequence(
            SchedulingChange.ROSTER_SUPERSEDED,
            SchedulingChange.ROSTER_PUBLISHED,
            SchedulingChange.SWAP_LAPSED);
  }

  @Test
  void GivenALapsedSwap_WhenApproving_ThenItIsRefusedAsNoLongerPending() {
    SwapRequestId request = pendingOnARosterThenReplaced();

    assertThatThrownBy(() -> service.approveSwap(scheduler(ACME), ACME, request))
        .isInstanceOf(SwapNotPendingException.class);
  }

  /** Lapsing follows supersession, and a roster for another week supersedes nothing. */
  @Test
  void GivenAPendingSwap_WhenARosterForAnotherWeekIsPublished_ThenItIsStillPending() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    Roster nextWeek =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, NEXT_WEEK));

    scheduling.publish(scheduler(ACME), ACME, nextWeek.id());

    assertThat(swaps.byId(request).status()).isEqualTo(SwapRequestStatus.REQUESTED);
  }

  /**
   * Approving one swap publishes a replacement, which supersedes the roster every other request on
   * it names. Those lapse, as R33 says; the one being approved does not, because approving is what
   * settled it.
   */
  @Test
  void GivenTwoPendingSwapsOnOneRoster_WhenOneIsApproved_ThenOnlyTheOtherLapses() {
    TwoPending two = twoPendingOnOneRoster();

    service.approveSwap(scheduler(ACME), ACME, two.adas());

    assertThat(swaps.byId(two.adas()).status()).isEqualTo(SwapRequestStatus.APPROVED);
    assertThat(swaps.byId(two.alans()).status()).isEqualTo(SwapRequestStatus.LAPSED);
  }

  /** The approved request's parties hear that it was approved, and never that it lapsed. */
  @Test
  void GivenTwoPendingSwapsOnOneRoster_WhenOneIsApproved_ThenEachIsToldOnlyWhatBecameOfTheirs() {
    TwoPending two = twoPendingOnOneRoster();
    notifier.told.clear();

    service.approveSwap(scheduler(ACME), ACME, two.adas());

    assertThat(notifier.told)
        .containsExactlyInAnyOrder(
            new Told("APPROVED", grace, two.adas()),
            new Told("APPROVED", ada, two.adas()),
            new Told("LAPSED", two.alan(), two.alans()),
            new Told("LAPSED", grace, two.alans()));
  }

  /** One publication can supersede several rosters, and each takes its pending requests with it. */
  @Test
  void GivenPendingSwapsOnTwoRosters_WhenOnePublicationSupersedesBoth_ThenBothLapse() {
    ShiftId monday = aShift(Set.of());
    Roster thisWeek = publishedWith(ada, monday);
    ShiftId nextMonday =
        scheduling.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, NEXT_MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster nextDraft =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, NEXT_WEEK));
    scheduling.assign(
        scheduler(ACME), new AssignToShiftCommand(ACME, nextDraft.id(), nextMonday, ada));
    Roster nextWeek = scheduling.publish(scheduler(ACME), ACME, nextDraft.id());
    SwapRequestId first =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, thisWeek.id(), monday, ada, grace));
    SwapRequestId second =
        service.requestSwap(
            themself(ACME, ada),
            new RequestSwapCommand(ACME, nextWeek.id(), nextMonday, ada, grace));
    Roster bothWeeks =
        scheduling.generateDraft(
            scheduler(ACME),
            new GenerateRosterCommand(ACME, new TimeRange(THE_WEEK.start(), NEXT_WEEK.end())));

    scheduling.publish(scheduler(ACME), ACME, bothWeeks.id());

    assertThat(swaps.byId(first).status()).isEqualTo(SwapRequestStatus.LAPSED);
    assertThat(swaps.byId(second).status()).isEqualTo(SwapRequestStatus.LAPSED);
  }

  /**
   * A lapse that cannot be stored refuses the whole publication, and the transaction takes back
   * every write before it, but never an audit line or a notification once raised. So every lapse is
   * stored before anything is recorded or told, and a refusal part-way leaves no account of the
   * lapses before it, nor of a supersession that never happened.
   */
  @Test
  void GivenALapseThatCannotBeStored_WhenPublishing_ThenNothingIsRecordedOrToldFirst() {
    twoPendingOnOneRoster();
    SchedulingService publishing =
        new SchedulingService(
            shifts,
            rosters,
            constraintSets,
            new RefusesTheSecondLapse(swaps),
            workforce,
            new AssignsNobody(),
            notifier,
            audit,
            Clock.fixed(RAISED_AT, ZoneOffset.UTC));
    Roster replacement =
        publishing.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    notifier.told.clear();
    audit.forget();

    assertThatThrownBy(() -> publishing.publish(scheduler(ACME), ACME, replacement.id()))
        .isInstanceOf(SwapNotPendingException.class);

    assertThat(audit.changes()).isEmpty();
    assertThat(notifier.told).isEmpty();
  }

  /**
   * Stands for a writer that skipped the publication lock and decided a request while a publication
   * was lapsing it: the second lapse finds it no longer pending.
   */
  private static final class RefusesTheSecondLapse implements SwapRequestRepository {

    private final SwapRequestRepository store;
    private int lapses;

    RefusesTheSecondLapse(SwapRequestRepository store) {
      this.store = store;
    }

    @Override
    public void save(SwapRequest request) {
      if (request.status() == SwapRequestStatus.LAPSED && ++lapses == 2) {
        throw new SwapNotPendingException(request.id(), SwapRequestStatus.REJECTED);
      }
      store.save(request);
    }

    @Override
    public Optional<SwapRequest> findById(OrganisationId organisation, SwapRequestId id) {
      return store.findById(organisation, id);
    }

    @Override
    public List<SwapRequest> findByOrganisation(OrganisationId organisation) {
      return store.findByOrganisation(organisation);
    }

    @Override
    public List<SwapRequest> findPendingNaming(OrganisationId organisation, RosterId roster) {
      return store.findPendingNaming(organisation, roster);
    }
  }

  private record TwoPending(SwapRequestId adas, SwapRequestId alans, EmployeeId alan) {}

  /** Ada on Monday and Alan on Tuesday, each asking Grace, on one published roster. */
  private TwoPending twoPendingOnOneRoster() {
    EmployeeId alan = available(ACME, "Alan", Set.of());
    ShiftId monday = aShift(Set.of());
    ShiftId tuesday =
        scheduling.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, TUESDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster draft =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    scheduling.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), monday, ada));
    scheduling.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), tuesday, alan));
    Roster original = scheduling.publish(scheduler(ACME), ACME, draft.id());
    SwapRequestId adas =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), monday, ada, grace));
    SwapRequestId alans =
        service.requestSwap(
            themself(ACME, alan),
            new RequestSwapCommand(ACME, original.id(), tuesday, alan, grace));
    return new TwoPending(adas, alans, alan);
  }

  // publishApproving is how approval tells a publication which request it settles. It refuses a
  // request it could not have been asked to settle, before it writes anything.

  @Test
  void GivenARequestStillPending_WhenPublishedAsApproved_ThenItIsRefusedAndNothingChanges() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    Roster replacement =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));

    assertThatThrownBy(
            () ->
                scheduling.publishApproving(
                    scheduler(ACME), ACME, replacement.id(), swaps.byId(request)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rosters.byId(original.id()).status()).isEqualTo(RosterStatus.PUBLISHED);
  }

  @Test
  void GivenAnotherOrganisationsRequest_WhenPublishedAsApproved_ThenItIsRefusedAndNothingChanges() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequest theirs =
        new SwapRequest(SwapRequestId.newId(), RIVAL, original.id(), shift, ada, grace, RAISED_AT)
            .approved();
    Roster replacement =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));

    assertThatThrownBy(
            () -> scheduling.publishApproving(scheduler(ACME), ACME, replacement.id(), theirs))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rosters.byId(original.id()).status()).isEqualTo(RosterStatus.PUBLISHED);
  }

  @Test
  void GivenARequestOnARosterThisDoesNotSupersede_WhenPublishedAsApproved_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequest elsewhere =
        new SwapRequest(SwapRequestId.newId(), ACME, RosterId.newId(), shift, ada, grace, RAISED_AT)
            .approved();
    Roster replacement =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));

    assertThatThrownBy(
            () -> scheduling.publishApproving(scheduler(ACME), ACME, replacement.id(), elsewhere))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rosters.byId(original.id()).status()).isEqualTo(RosterStatus.PUBLISHED);
  }

  /** Not one a Scheduler was asked: a request nobody raised cannot be approved into existence. */
  @Test
  void GivenARequestNobodyStored_WhenPublishedAsApproved_ThenItIsRefusedAndNothingChanges() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequest neverRaised =
        new SwapRequest(SwapRequestId.newId(), ACME, original.id(), shift, ada, grace, RAISED_AT)
            .approved();
    Roster replacement = replacementWith(grace, shift);

    assertThatThrownBy(
            () -> scheduling.publishApproving(scheduler(ACME), ACME, replacement.id(), neverRaised))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rosters.byId(original.id()).status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(swaps.byId(neverRaised.id())).isNull();
  }

  /** The stored request is the one asked; approving it with other parties would rewrite it. */
  @Test
  void GivenARequestWithOtherPartiesThanStored_WhenPublishedAsApproved_ThenItIsRefused() {
    EmployeeId alan = available(ACME, "Alan", Set.of());
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    SwapRequest toAlanInstead =
        new SwapRequest(request, ACME, original.id(), shift, ada, alan, RAISED_AT).approved();
    Roster replacement = replacementWith(alan, shift);

    assertThatThrownBy(
            () ->
                scheduling.publishApproving(scheduler(ACME), ACME, replacement.id(), toAlanInstead))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(swaps.byId(request).counterparty()).isEqualTo(grace);
    assertThat(swaps.byId(request).status()).isEqualTo(SwapRequestStatus.REQUESTED);
  }

  /** Approving says the roster changed; a replacement that does not carry the swap has not. */
  @Test
  void GivenAReplacementThatDoesNotCarryTheSwap_WhenPublishedAsApproved_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    Roster empty =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));

    assertThatThrownBy(
            () ->
                scheduling.publishApproving(
                    scheduler(ACME), ACME, empty.id(), swaps.byId(request).approved()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rosters.byId(original.id()).status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(swaps.byId(request).status()).isEqualTo(SwapRequestStatus.REQUESTED);
  }

  /** A draft over THE_WEEK carrying one person on one shift, as a swap's replacement would. */
  private Roster replacementWith(EmployeeId who, ShiftId shift) {
    Roster draft =
        scheduling.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    return scheduling.assign(
        scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, who));
  }

  /**
   * A request read against a roster that a publication in flight is about to supersede would be
   * stored after that publication looked for requests to lapse, and wait forever on a roster nobody
   * works to. Raising one takes turns with publications, so it sees what they committed.
   */
  @Test
  void GivenAPublishedRoster_WhenAskingForASwap_ThenTheOrganisationsPublicationsWaitFirst() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    log.forget();

    service.requestSwap(
        themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));

    assertThat(log.calls()).first().isEqualTo(CallLog.Call.LOCK_PUBLICATIONS);
  }

  /**
   * A rejection takes turns with publications too. Otherwise a publication could read a request as
   * pending, the rejection commit, and the lapse then be refused as a decision over a decision,
   * taking the whole publication down with it.
   */
  @Test
  void
      GivenAPendingSwap_WhenRejecting_ThenTheOrganisationsPublicationsWaitBeforeTheRequestIsRead() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    SwapRequestId request =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    log.forget();

    service.rejectSwap(scheduler(ACME), ACME, request);

    assertThat(log.calls()).first().isEqualTo(CallLog.Call.LOCK_PUBLICATIONS);
  }

  // --- R32: an assignment has at most one pending swap request ------------------------------

  @Test
  void GivenAPendingSwap_WhenTheSameShiftIsAskedForAgain_ThenItIsRefused() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    RequestSwapCommand asking = new RequestSwapCommand(ACME, original.id(), shift, ada, grace);
    service.requestSwap(themself(ACME, ada), asking);

    assertThatThrownBy(() -> service.requestSwap(themself(ACME, ada), asking))
        .isInstanceOf(SwapAlreadyRequestedException.class);
  }

  /**
   * One per assignment, not one per pair: asking somebody else while the first is still waiting
   * would let two Schedulers approve two different handovers of one shift.
   */
  @Test
  void GivenAPendingSwap_WhenTheSameShiftIsAskedOfAnotherColleague_ThenItIsStillRefused() {
    EmployeeId alan = available(ACME, "Alan", Set.of());
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    service.requestSwap(
        themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));

    assertThatThrownBy(
            () ->
                service.requestSwap(
                    themself(ACME, ada),
                    new RequestSwapCommand(ACME, original.id(), shift, ada, alan)))
        .isInstanceOf(SwapAlreadyRequestedException.class);
  }

  @Test
  void GivenARefusedAskForTheSameShift_WhenItIsRefused_ThenNothingIsStoredOrToldForIt() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    RequestSwapCommand asking = new RequestSwapCommand(ACME, original.id(), shift, ada, grace);
    service.requestSwap(themself(ACME, ada), asking);
    notifier.told.clear();
    audit.forget();

    assertThatThrownBy(() -> service.requestSwap(themself(ACME, ada), asking))
        .isInstanceOf(SwapAlreadyRequestedException.class);

    assertThat(swaps.findByOrganisation(ACME)).hasSize(1);
    assertThat(notifier.told).isEmpty();
    assertThat(audit.changes()).doesNotContain(SchedulingChange.SWAP_REQUESTED);
  }

  /** A rejected request no longer waits on anybody, so it no longer holds the assignment. */
  @Test
  void GivenARejectedSwap_WhenTheSameShiftIsAskedForAgain_ThenItIsAccepted() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    RequestSwapCommand asking = new RequestSwapCommand(ACME, original.id(), shift, ada, grace);
    SwapRequestId first = service.requestSwap(themself(ACME, ada), asking);
    service.rejectSwap(scheduler(ACME), ACME, first);

    SwapRequestId second = service.requestSwap(themself(ACME, ada), asking);

    assertThat(swaps.byId(second).status()).isEqualTo(SwapRequestStatus.REQUESTED);
  }

  /**
   * A lapsed request named the roster that was replaced. The shift on the replacement is another
   * assignment, so asking for it again is a new request rather than a second one.
   */
  @Test
  void GivenALapsedSwap_WhenTheShiftIsAskedForOnTheReplacement_ThenItIsAccepted() {
    ShiftId shift = aShift(Set.of());
    Roster original = publishedWith(ada, shift);
    service.requestSwap(
        themself(ACME, ada), new RequestSwapCommand(ACME, original.id(), shift, ada, grace));
    Roster replacement = publishedWith(ada, shift);

    SwapRequestId again =
        service.requestSwap(
            themself(ACME, ada), new RequestSwapCommand(ACME, replacement.id(), shift, ada, grace));

    assertThat(swaps.byId(again).status()).isEqualTo(SwapRequestStatus.REQUESTED);
  }

  // --- ADR-030: the platform operator is refused by every tenant use case -------------------
  //
  // One test per driving-port method rather than a table, because each of these is a permission
  // decision and a table would hide which row matters.

  @Test
  void GivenTheOperator_WhenRequestingASwap_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.requestSwap(
                    anOperator(),
                    new RequestSwapCommand(
                        ACME,
                        RosterId.newId(),
                        ShiftId.newId(),
                        EmployeeId.newId(),
                        EmployeeId.newId())))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenListingSwapRequests_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findSwapRequests(anOperator(), ACME))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenReadingASwapRequest_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findSwapRequest(anOperator(), ACME, SwapRequestId.newId()))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenApprovingASwap_ThenItIsRefused() {
    assertThatThrownBy(() -> service.approveSwap(anOperator(), ACME, SwapRequestId.newId()))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenRejectingASwap_ThenItIsRefused() {
    assertThatThrownBy(() -> service.rejectSwap(anOperator(), ACME, SwapRequestId.newId()))
        .isInstanceOf(NotATenantMemberException.class);
  }

  private static Operator anOperator() {
    return new Operator(UserId.newId());
  }
}
