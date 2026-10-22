package io.github.naimelijah.hrsched.scheduling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.naimelijah.hrsched.scheduling.application.port.in.AssignToShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.DefineShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RemoveAssignmentCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseConstraintsCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseObjectivesCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RosterAssessment;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RosteredShift;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SchedulingChange;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeNotQualifiedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeUnavailableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.DuplicateAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.HeldWork;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidObjectiveWeightException;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.NoEmployeeRecordException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Objective;
import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;
import io.github.naimelijah.hrsched.scheduling.domain.model.OverlappingAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterMeasures;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterScore;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftOutsideRosterPeriodException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shortfall;
import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssigneeException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRequiredQualificationException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownShiftException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Specification of the scheduling use cases, driven without Spring.
 *
 * <p>The case that matters most here is publication being refused. A roster that breaks the rules
 * cannot be published and there is no override, so that path needs a test that reaches it rather
 * than one that trusts the generator never to produce a bad roster.
 *
 * <p>Every constraint value below is invented to make a specific case pass or fail. None of them is
 * taken from any jurisdiction and none says anything about the law.
 */
class SchedulingServiceTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final TimeRange MONDAY_DAY =
      new TimeRange(Instant.parse("2026-10-05T08:00:00Z"), Instant.parse("2026-10-05T16:00:00Z"));
  private static final TimeRange THE_WEEK =
      new TimeRange(Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z"));
  private static final TimeRange NEXT_WEEK =
      new TimeRange(Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-19T00:00:00Z"));

  /** Runs past the end of THE_WEEK into NEXT_WEEK, so either roster may carry it (R35). */
  private static final TimeRange SUNDAY_NIGHT =
      new TimeRange(Instant.parse("2026-10-11T22:00:00Z"), Instant.parse("2026-10-12T06:00:00Z"));

  private static final TimeRange NEXT_MONDAY_EARLY =
      new TimeRange(Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-12T08:00:00Z"));

  /** What the clock reads in these tests, unless a test says otherwise. Any instant would do. */
  private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

  private FakeShifts shifts;
  private FakeRosters rosters;
  private FakeConstraintSets constraintSets;
  private FakeObjectiveWeights objectiveWeights;
  private FakeWorkforce workforce;
  private RecordingAuditTrail audit;
  private SchedulingService service;

  @BeforeEach
  void setUp() {
    shifts = new FakeShifts();
    rosters = new FakeRosters();
    constraintSets = new FakeConstraintSets();
    objectiveWeights = new FakeObjectiveWeights();
    workforce = new FakeWorkforce();
    audit = new RecordingAuditTrail();
    service = withClock(Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private SchedulingService withClock(Clock clock) {
    return with(clock, new FirstFitGenerator());
  }

  /** The service, generating with {@code generator} instead of the first-fit double. */
  private SchedulingService generatingWith(RosterGenerator generator) {
    return with(Clock.fixed(NOW, ZoneOffset.UTC), generator);
  }

  private SchedulingService with(Clock clock, RosterGenerator generator) {
    return new SchedulingService(
        shifts,
        rosters,
        constraintSets,
        objectiveWeights,
        new FakeSwaps(new CallLog()),
        workforce,
        generator,
        new RecordingUnitsOfWork(),
        new RecordingNotifier(),
        audit,
        clock);
  }

  /** Reads a second later every time it is asked, so a use case reading it twice is caught. */
  private static final class AdvancingClock extends Clock {

    private Instant next;

    AdvancingClock(Instant start) {
      next = start;
    }

    @Override
    public Instant instant() {
      Instant now = next;
      next = next.plusSeconds(1);
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException("these tests read instants only");
    }
  }

  /** Generates and publishes a roster for THE_WEEK, which supersedes any published before it. */
  private Roster publishTheWeek() {
    Roster draft =
        service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    service.publish(scheduler(ACME), ACME, draft.id());
    return rosters.byId(draft.id());
  }

  private static TenantActor scheduler(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.SCHEDULER);
  }

  private static TenantActor admin(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.ADMIN);
  }

  private EmployeeId available(OrganisationId organisation, String name) {
    EmployeeId id = EmployeeId.newId();
    workforce.add(
        organisation, new RosterCandidate(id, name, Set.of(), List.of(), StatedPreferences.none()));
    return id;
  }

  @Test
  void GivenAScheduler_WhenGeneratingAndPublishing_ThenTheConstraintVersionIsRecorded() {
    available(ACME, "Ada");
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));

    Roster draft =
        service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    Roster published = service.publish(scheduler(ACME), ACME, draft.id());

    assertThat(published.status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(published.validatedAgainstConstraintVersion())
        .isEqualTo(ConstraintSet.initial(ACME).version());
  }

  @Test
  void GivenRulesTightenedAfterGeneration_WhenPublishing_ThenItIsRefusedWithEveryViolation() {
    available(ACME, "Ada");
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster draft =
        service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));

    // Invented: one hour a day, which the eight-hour shift already assigned cannot satisfy.
    service.reviseConstraints(
        admin(ACME),
        new ReviseConstraintsCommand(
            ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(1)))));

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, draft.id()))
        .describedAs("a roster breaking the rules cannot be published, and there is no override")
        .isInstanceOf(RosterNotCompliantException.class);
    assertThat(rosters.byId(draft.id()).status()).isEqualTo(RosterStatus.DRAFT);
  }

  /**
   * Q9: a published roster keeps the version that approved it, and tightening a rule afterwards is
   * never retroactive. The roster names a number; this is the number turned back into the rules, so
   * anybody asking what the roster was judged against gets the answer that held then.
   */
  @Test
  void
      GivenRulesRevisedAfterPublication_WhenTheRosterVersionIsReadBack_ThenItHasTheRulesItPassed() {
    available(ACME, "Ada");
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    // Invented: ten hours a day, which the eight-hour shift satisfies.
    ConstraintSet approving =
        service.reviseConstraints(
            admin(ACME),
            new ReviseConstraintsCommand(
                ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(10)))));
    Roster published = publishTheWeek();
    // Invented: one hour a day, which that same roster would now break.
    service.reviseConstraints(
        admin(ACME),
        new ReviseConstraintsCommand(
            ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(1)))));

    assertThat(
            service.findConstraints(
                admin(ACME), ACME, published.validatedAgainstConstraintVersion()))
        .contains(approving);
  }

  /**
   * Version one is never stored: reading the rules stores nothing, and the first revision writes
   * version two. A roster published before anybody revised anything still names version one, and
   * the rules that approved it were the empty set, so that is what reading it back answers — even
   * after the rules have changed since.
   */
  @Test
  void GivenRulesRevisedSince_WhenTheFirstVersionIsReadBack_ThenItIsTheEmptySetThatApprovedIt() {
    available(ACME, "Ada");
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster published = publishTheWeek();
    // Invented: one hour a day.
    service.reviseConstraints(
        admin(ACME),
        new ReviseConstraintsCommand(
            ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(1)))));

    assertThat(
            service.findConstraints(
                admin(ACME), ACME, published.validatedAgainstConstraintVersion()))
        .contains(ConstraintSet.initial(ACME));
  }

  @Test
  void GivenAVersionNeverWritten_WhenReadBack_ThenNothingIsFound() {
    service.reviseConstraints(admin(ACME), new ReviseConstraintsCommand(ACME, List.of()));

    assertThat(service.findConstraints(admin(ACME), ACME, 3)).isEmpty();
  }

  /** F21: any member may see the rules, and an earlier version is the rules as they were. */
  @Test
  void GivenAnEmployee_WhenReadingAnEarlierVersion_ThenItIsAnswered() {
    service.reviseConstraints(admin(ACME), new ReviseConstraintsCommand(ACME, List.of()));

    assertThat(
            service.findConstraints(new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE), ACME, 1))
        .contains(ConstraintSet.initial(ACME));
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenReadingOurEarlierVersion_ThenItIsRefused() {
    service.reviseConstraints(admin(ACME), new ReviseConstraintsCommand(ACME, List.of()));

    assertThatThrownBy(() -> service.findConstraints(admin(RIVAL), ACME, 2))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnAssignedShiftThatCannotBeResolved_WhenPublishing_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft =
        new Roster(RosterId.newId(), ACME, THE_WEEK)
            .withAssignments(List.of(new Assignment(ShiftId.newId(), ada)));
    rosters.save(draft);

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, draft.id()))
        .describedAs("work that cannot be checked must refuse publication, not be skipped")
        .isInstanceOf(RosterNotVerifiableException.class);
  }

  @Test
  void
      GivenAnEarlierPublishedRosterForTheSameWeek_WhenPublishingAgain_ThenTheEarlierIsSuperseded() {
    available(ACME, "Ada");
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster first =
        service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    service.publish(scheduler(ACME), ACME, first.id());

    Roster second =
        service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    service.publish(scheduler(ACME), ACME, second.id());

    assertThat(rosters.byId(first.id()).status())
        .describedAs("two rosters must never both be authoritative for the same week")
        .isEqualTo(RosterStatus.SUPERSEDED);
    assertThat(rosters.byId(second.id()).status()).isEqualTo(RosterStatus.PUBLISHED);
  }

  /**
   * The roster superseded stops governing at the very moment its replacement starts to, so at no
   * moment of record do both govern, and at none does neither (R37).
   */
  @Test
  void
      GivenAPublicationThatSupersedesAnother_WhenRecorded_ThenOneMomentEndsTheOneAndBeginsTheOther() {
    service = withClock(new AdvancingClock(NOW));
    available(ACME, "Ada");
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster first = publishTheWeek();

    Roster second = publishTheWeek();

    assertThat(rosters.byId(first.id()).supersededAt())
        .describedAs("read from the clock once, or the two moments would differ")
        .isEqualTo(second.publishedAt());
    assertThat(second.publishedAt()).isAfter(first.publishedAt());
  }

  /**
   * A clock that has not moved since the last publication would begin and end the first roster's
   * authority at one instant, recording that it never governed. Record time moves on instead.
   */
  @Test
  void GivenAClockThatHasNotMoved_WhenPublishingAgain_ThenItIsRecordedAMicrosecondLater() {
    available(ACME, "Ada");
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster first = publishTheWeek();

    Roster second = publishTheWeek();

    assertThat(first.publishedAt()).isEqualTo(NOW);
    assertThat(second.publishedAt()).isEqualTo(NOW.plus(1, ChronoUnit.MICROS));
    assertThat(rosters.byId(first.id()).supersededAt()).isEqualTo(second.publishedAt());
  }

  @Test
  void GivenAnAdmin_WhenGeneratingARoster_ThenItIsRefused() {
    assertThatThrownBy(
            () -> service.generateDraft(admin(ACME), new GenerateRosterCommand(ACME, THE_WEEK)))
        .describedAs("an Admin manages people and rules; building rosters is the Scheduler's")
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAScheduler_WhenConfiguringConstraints_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.reviseConstraints(
                    scheduler(ACME), new ReviseConstraintsCommand(ACME, List.of())))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAQualificationNobodyDefined_WhenDefiningAShift_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.defineShift(
                    scheduler(ACME),
                    new DefineShiftCommand(
                        ACME,
                        MONDAY_DAY,
                        ZoneOffset.UTC,
                        1,
                        Set.of(QualificationId.newId()),
                        false)))
        .describedAs("a shift nobody could ever cover is an error, not a permanent silent gap")
        .isInstanceOf(UnknownRequiredQualificationException.class);
  }

  @Test
  void GivenAQualificationTheOrganisationDefined_WhenDefiningAShift_ThenItIsAccepted() {
    QualificationId forklift = QualificationId.newId();
    workforce.define(ACME, forklift);

    assertThatCode(
            () ->
                service.defineShift(
                    scheduler(ACME),
                    new DefineShiftCommand(
                        ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(forklift), false)))
        .doesNotThrowAnyException();
  }

  @Test
  void GivenASchedulerOfAnotherOrganisation_WhenGeneratingARoster_ThenItIsNotTheirs() {
    assertThatThrownBy(
            () ->
                service.generateDraft(scheduler(RIVAL), new GenerateRosterCommand(ACME, THE_WEEK)))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  /**
   * Flipped by ADR-030. A roster is personal data about every colleague on it, and the operator is
   * nobody's colleague; reading one was the sharpest consequence of the old bypass.
   */
  @Test
  void GivenTheOperator_WhenReadingARoster_ThenItIsRefused() {
    available(ACME, "Ada");
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster draft =
        service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));

    Actor operator = new Operator(UserId.newId());

    assertThatThrownBy(() -> service.findRoster(operator, ACME, draft.id()))
        .isInstanceOf(NotATenantMemberException.class);
  }

  // --- #28: what a generator answers is checked before it is stored ----------------------------

  /**
   * A generator is ours, and so is its promise never to break a rule. An answer breaking it is a
   * defect in that generator, refused loudly before anything is stored: saved, it would be a draft
   * publication refuses only later, and a refusal nobody asked for.
   */
  @Test
  void GivenAGeneratorPuttingSomebodyInTwoPlacesAtOnce_WhenGenerating_ThenNothingIsStored() {
    EmployeeId ada = available(ACME, "Ada");
    ShiftId day = aShift(ACME, Set.of());
    ShiftId sameDay = aShift(ACME, Set.of());

    assertGenerationRefused(
        request -> List.of(new Assignment(day, ada), new Assignment(sameDay, ada)));
  }

  @Test
  void GivenAGeneratorBreakingAWorkingTimeRule_WhenGenerating_ThenNothingIsStored() {
    EmployeeId ada = available(ACME, "Ada");
    ShiftId day = aShift(ACME, Set.of());
    // Invented: one hour a day, which the eight-hour shift cannot satisfy.
    service.reviseConstraints(
        admin(ACME),
        new ReviseConstraintsCommand(
            ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(1)))));

    assertGenerationRefused(request -> List.of(new Assignment(day, ada)));
  }

  @Test
  void
      GivenAGeneratorRosteringSomebodyThroughTheirDeclaredAbsence_WhenGenerating_ThenNothingIsStored() {
    EmployeeId ada = EmployeeId.newId();
    workforce.add(
        ACME,
        new RosterCandidate(ada, "Ada", Set.of(), List.of(MONDAY_DAY), StatedPreferences.none()));
    ShiftId day = aShift(ACME, Set.of());

    assertGenerationRefused(request -> List.of(new Assignment(day, ada)));
  }

  @Test
  void GivenAGeneratorNamingAShiftItWasNotAskedToCover_WhenGenerating_ThenNothingIsStored() {
    EmployeeId ada = available(ACME, "Ada");

    assertGenerationRefused(request -> List.of(new Assignment(ShiftId.newId(), ada)));
  }

  @Test
  void GivenAGeneratorNamingSomebodyWhoIsNotACandidate_WhenGenerating_ThenNothingIsStored() {
    ShiftId day = aShift(ACME, Set.of());

    assertGenerationRefused(request -> List.of(new Assignment(day, EmployeeId.newId())));
  }

  @Test
  void GivenAGeneratorStaffingAShiftAboveItsHeadcount_WhenGenerating_ThenNothingIsStored() {
    EmployeeId ada = available(ACME, "Ada");
    EmployeeId grace = available(ACME, "Grace");
    ShiftId needsOne = aShift(ACME, Set.of());

    assertGenerationRefused(
        request -> List.of(new Assignment(needsOne, ada), new Assignment(needsOne, grace)));
  }

  @Test
  void GivenAGeneratorAddingOnePersonToOneShiftTwice_WhenGenerating_ThenNothingIsStored() {
    EmployeeId ada = available(ACME, "Ada");
    ShiftId day = aShift(ACME, Set.of());

    assertGenerationRefused(request -> List.of(new Assignment(day, ada), new Assignment(day, ada)));
  }

  /**
   * ADR-045: generation reads in one unit of work, generates outside any, and stores the draft in
   * another, so a solve running for seconds holds no connection.
   */
  @Test
  void GivenADraftToGenerate_WhenGenerating_ThenTheGeneratorWorksOutsideAnyUnitOfWork() {
    available(ACME, "Ada");
    aShift(ACME, Set.of());
    RecordingUnitsOfWork units = new RecordingUnitsOfWork();
    List<Optional<RecordingUnitsOfWork.Unit>> generatedIn = new ArrayList<>();
    List<Optional<RecordingUnitsOfWork.Unit>> savedIn = new ArrayList<>();
    rosters.observeSaves(() -> savedIn.add(units.open()));
    SchedulingService generating =
        new SchedulingService(
            shifts,
            rosters,
            constraintSets,
            objectiveWeights,
            new FakeSwaps(new CallLog()),
            workforce,
            request -> {
              generatedIn.add(units.open());
              return new FirstFitGenerator().generate(request);
            },
            units,
            new RecordingNotifier(),
            audit,
            Clock.fixed(NOW, ZoneOffset.UTC));

    Roster draft =
        generating.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));

    assertThat(draft.assignments()).hasSize(1);
    assertThat(generatedIn).containsExactly(Optional.empty());
    assertThat(savedIn).containsExactly(Optional.of(RecordingUnitsOfWork.Unit.WRITING));
    assertThat(units.begun())
        .containsExactly(RecordingUnitsOfWork.Unit.READING, RecordingUnitsOfWork.Unit.WRITING);
  }

  /**
   * Generating with {@code generator} is refused as a defect in it, and leaves neither a draft nor
   * an audit line behind.
   */
  private void assertGenerationRefused(RosterGenerator generator) {
    audit.forget();

    assertThatThrownBy(
            () ->
                generatingWith(generator)
                    .generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(rosters.findByOrganisation(ACME)).isEmpty();
    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenShiftsInsideAndOutsideASpan_WhenListingThem_ThenOnlyTheOverlappingOnesComeBack() {
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    TimeRange nextMonth =
        new TimeRange(Instant.parse("2026-11-09T08:00:00Z"), Instant.parse("2026-11-09T16:00:00Z"));
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, nextMonth, ZoneOffset.UTC, 1, Set.of(), false));

    assertThat(service.findShifts(scheduler(ACME), ACME, THE_WEEK))
        .extracting(Shift::period)
        .containsExactly(MONDAY_DAY);
  }

  @Test
  void GivenAnotherOrganisationsShifts_WhenListingThem_ThenNothingLeaks() {
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));

    assertThatThrownBy(() -> service.findShifts(scheduler(RIVAL), ACME, THE_WEEK))
        .isInstanceOf(CrossTenantAccessException.class);
    assertThat(service.findShifts(scheduler(RIVAL), RIVAL, THE_WEEK)).isEmpty();
  }

  @Test
  void GivenADefinedShift_WhenAnEmployeeReadsItByIdentity_ThenItIsReturned() {
    ShiftId id =
        service.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));

    assertThat(service.findShift(new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE), ACME, id))
        .map(Shift::period)
        .contains(MONDAY_DAY);
  }

  @Test
  void GivenAnotherOrganisationsShift_WhenReadByIdentity_ThenNothingLeaks() {
    ShiftId theirs =
        service.defineShift(
            scheduler(RIVAL),
            new DefineShiftCommand(RIVAL, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));

    assertThatThrownBy(() -> service.findShift(scheduler(RIVAL), ACME, theirs))
        .isInstanceOf(CrossTenantAccessException.class);
    assertThat(service.findShift(scheduler(ACME), ACME, theirs)).isEmpty();
  }

  @Test
  void GivenRostersOfTwoOrganisations_WhenListingThem_ThenOnlyTheCallersComeBack() {
    available(ACME, "Ada");
    service.defineShift(
        scheduler(ACME),
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster ours = service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    service.generateDraft(scheduler(RIVAL), new GenerateRosterCommand(RIVAL, THE_WEEK));

    assertThat(service.findRosters(scheduler(ACME), ACME))
        .extracting(Roster::id)
        .containsExactly(ours.id());
  }

  @Test
  void GivenAScheduler_WhenDefiningAShift_ThenTheChangeIsRecordedAgainstThem() {
    TenantActor scheduler = scheduler(ACME);

    ShiftId shift =
        service.defineShift(
            scheduler,
            new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));

    assertThat(audit.of(SchedulingChange.SHIFT_DEFINED))
        .containsExactly(
            new AuditedChange(ACME, scheduler.id(), SchedulingChange.SHIFT_DEFINED, shift.value()));
  }

  @Test
  void GivenAnAdmin_WhenRevisingTheRules_ThenTheOrganisationIsTheSubject() {
    TenantActor admin = admin(ACME);

    service.reviseConstraints(admin, new ReviseConstraintsCommand(ACME, List.of()));

    assertThat(audit.of(SchedulingChange.CONSTRAINTS_REVISED))
        .containsExactly(
            new AuditedChange(
                ACME, admin.id(), SchedulingChange.CONSTRAINTS_REVISED, ACME.value()));
  }

  // --- F35, F36: how an organisation weights the objectives (ADR-010). Every weight is invented.

  private static ReviseObjectivesCommand weighting(OrganisationId organisation, int fairness) {
    return new ReviseObjectivesCommand(organisation, Map.of(Objective.FAIRNESS, fairness));
  }

  @Test
  void GivenAnAdmin_WhenWeightingTheObjectives_ThenTheWeightsAreReadBack() {
    service.reviseObjectives(admin(ACME), weighting(ACME, 3));

    assertThat(service.findObjectives(scheduler(ACME), ACME).weightOf(Objective.FAIRNESS))
        .contains(3);
  }

  /** Nothing weighted is a configuration, not a missing record: every roster scores alike. */
  @Test
  void GivenNothingWeighted_WhenReadingTheWeights_ThenNoObjectiveIsWeighed() {
    assertThat(service.findObjectives(admin(ACME), ACME)).isEqualTo(ObjectiveWeights.none(ACME));
  }

  /** F36: any member reads them, as any member reads the rules (F21). */
  @Test
  void GivenAnEmployee_WhenReadingTheWeights_ThenTheyAreShown() {
    service.reviseObjectives(admin(ACME), weighting(ACME, 2));

    assertThat(
            service
                .findObjectives(new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE), ACME)
                .weightOf(Objective.FAIRNESS))
        .contains(2);
  }

  /** Revision replaces the weights whole: what is left out stops being weighed. */
  @Test
  void GivenWeightsRevised_WhenAnObjectiveIsLeftOut_ThenItIsNoLongerWeighed() {
    service.reviseObjectives(admin(ACME), weighting(ACME, 3));

    service.reviseObjectives(
        admin(ACME), new ReviseObjectivesCommand(ACME, Map.of(Objective.DISRUPTION, 1)));

    ObjectiveWeights read = service.findObjectives(admin(ACME), ACME);
    assertThat(read.weightOf(Objective.FAIRNESS)).isEmpty();
    assertThat(read.weightOf(Objective.DISRUPTION)).contains(1);
  }

  @Test
  void GivenAScheduler_WhenWeightingTheObjectives_ThenItIsRefusedAndNothingIsStored() {
    assertThatThrownBy(() -> service.reviseObjectives(scheduler(ACME), weighting(ACME, 3)))
        .describedAs("weighing what makes a roster better is the Admin's, as the rules are")
        .isInstanceOf(NotPermittedException.class);
    assertThat(objectiveWeights.find(ACME)).isEmpty();
  }

  @Test
  void GivenAnEmployee_WhenWeightingTheObjectives_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.reviseObjectives(
                    new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE), weighting(ACME, 3)))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenWeightingOurObjectives_ThenItIsRefused() {
    assertThatThrownBy(() -> service.reviseObjectives(admin(RIVAL), weighting(ACME, 3)))
        .isInstanceOf(CrossTenantAccessException.class);
    assertThat(objectiveWeights.find(ACME)).isEmpty();
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenReadingOurWeights_ThenItIsRefused() {
    service.reviseObjectives(admin(ACME), weighting(ACME, 3));

    assertThatThrownBy(() -> service.findObjectives(admin(RIVAL), ACME))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenTheOperator_WhenWeightingTheObjectives_ThenItIsRefused() {
    assertThatThrownBy(
            () -> service.reviseObjectives(new Operator(UserId.newId()), weighting(ACME, 3)))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenReadingTheWeights_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findObjectives(new Operator(UserId.newId()), ACME))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenAnAdmin_WhenWeightingTheObjectives_ThenTheOrganisationIsTheSubject() {
    TenantActor admin = admin(ACME);

    service.reviseObjectives(admin, weighting(ACME, 3));

    assertThat(audit.of(SchedulingChange.OBJECTIVES_REVISED))
        .containsExactly(
            new AuditedChange(ACME, admin.id(), SchedulingChange.OBJECTIVES_REVISED, ACME.value()));
  }

  /** Refused before anything is written, so neither a row nor an audit line outlives it. */
  @Test
  void GivenAWeightBelowOne_WhenWeighting_ThenNothingIsStoredOrRecorded() {
    assertThatThrownBy(() -> service.reviseObjectives(admin(ACME), weighting(ACME, 0)))
        .isInstanceOf(InvalidObjectiveWeightException.class);
    assertThat(objectiveWeights.find(ACME)).isEmpty();
    assertThat(audit.of(SchedulingChange.OBJECTIVES_REVISED)).isEmpty();
  }

  @Test
  void GivenAScheduler_WhenPublishingARoster_ThenGenerationAndPublicationAreBothRecorded() {
    available(ACME, "Ada");
    TenantActor scheduler = scheduler(ACME);
    service.defineShift(
        scheduler, new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster draft = service.generateDraft(scheduler, new GenerateRosterCommand(ACME, THE_WEEK));

    service.publish(scheduler, ACME, draft.id());

    assertThat(audit.of(SchedulingChange.ROSTER_GENERATED))
        .containsExactly(
            new AuditedChange(
                ACME, scheduler.id(), SchedulingChange.ROSTER_GENERATED, draft.id().value()));
    assertThat(audit.of(SchedulingChange.ROSTER_PUBLISHED))
        .containsExactly(
            new AuditedChange(
                ACME, scheduler.id(), SchedulingChange.ROSTER_PUBLISHED, draft.id().value()));
  }

  /**
   * The demotion nobody asked for. Publishing one roster silently supersedes every published roster
   * it overlaps, so without an entry each, those rosters appear to have demoted themselves.
   */
  @Test
  void GivenAnEarlierPublishedRoster_WhenItIsSuperseded_ThenThatIsRecordedToo() {
    available(ACME, "Ada");
    TenantActor scheduler = scheduler(ACME);
    service.defineShift(
        scheduler, new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster first = service.generateDraft(scheduler, new GenerateRosterCommand(ACME, THE_WEEK));
    service.publish(scheduler, ACME, first.id());
    Roster second = service.generateDraft(scheduler, new GenerateRosterCommand(ACME, THE_WEEK));

    service.publish(scheduler, ACME, second.id());

    assertThat(audit.of(SchedulingChange.ROSTER_SUPERSEDED))
        .containsExactly(
            new AuditedChange(
                ACME, scheduler.id(), SchedulingChange.ROSTER_SUPERSEDED, first.id().value()));
  }

  @Test
  void GivenARosterBreakingTheRules_WhenPublicationIsRefused_ThenNothingIsRecordedAsPublished() {
    available(ACME, "Ada");
    TenantActor scheduler = scheduler(ACME);
    service.defineShift(
        scheduler, new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster draft = service.generateDraft(scheduler, new GenerateRosterCommand(ACME, THE_WEEK));
    // Invented: four hours a day, which the eight-hour shift already assigned cannot satisfy.
    service.reviseConstraints(
        admin(ACME),
        new ReviseConstraintsCommand(
            ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(4)))));

    assertThatThrownBy(() -> service.publish(scheduler, ACME, draft.id()))
        .isInstanceOf(RosterNotCompliantException.class);

    assertThat(audit.changes()).doesNotContain(SchedulingChange.ROSTER_PUBLISHED);
  }

  /**
   * Republishing a superseded roster would supersede the newer one in its place before finding out
   * it is not a draft. The transaction rolls that save back, but not the audit line, which would
   * record a demotion that never happened (#133).
   */
  @Test
  void
      GivenASupersededRosterThatANewerOneOverlaps_WhenPublishingItAgain_ThenItIsRefusedAndNothingIsRecorded() {
    available(ACME, "Ada");
    TenantActor scheduler = scheduler(ACME);
    service.defineShift(
        scheduler, new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster first = service.generateDraft(scheduler, new GenerateRosterCommand(ACME, THE_WEEK));
    service.publish(scheduler, ACME, first.id());
    Roster second = service.generateDraft(scheduler, new GenerateRosterCommand(ACME, THE_WEEK));
    service.publish(scheduler, ACME, second.id());
    audit.forget();

    assertThatThrownBy(() -> service.publish(scheduler, ACME, first.id()))
        .isInstanceOf(RosterNotEditableException.class);

    assertThat(rosters.byId(second.id()).status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(audit.changes()).isEmpty();
  }

  /**
   * A roster that is not a draft is refused for that, whatever else is wrong with it: the rules it
   * would break now are no reason to refuse something that could not be published anyway.
   */
  @Test
  void
      GivenAPublishedRosterTheRevisedRulesWouldRefuse_WhenPublishingItAgain_ThenItIsRefusedAsNotEditable() {
    available(ACME, "Ada");
    TenantActor scheduler = scheduler(ACME);
    service.defineShift(
        scheduler, new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    Roster published =
        service.publish(
            scheduler,
            ACME,
            service.generateDraft(scheduler, new GenerateRosterCommand(ACME, THE_WEEK)).id());
    // Invented: four hours a day, which the eight-hour shift already assigned cannot satisfy.
    service.reviseConstraints(
        admin(ACME),
        new ReviseConstraintsCommand(
            ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(4)))));

    assertThatThrownBy(() -> service.publish(scheduler, ACME, published.id()))
        .isInstanceOf(RosterNotEditableException.class);
  }

  /**
   * A refused act changed nothing, so it must leave no entry saying it did. An audit trail that
   * records attempts as though they were changes is worse than none: it is wrong.
   */
  @Test
  void GivenAnActorOfAnotherOrganisation_WhenTheyAreRefused_ThenNothingIsRecorded() {
    assertThatThrownBy(
            () ->
                service.defineShift(
                    scheduler(RIVAL),
                    new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false)))
        .isInstanceOf(CrossTenantAccessException.class);

    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenAnAdmin_WhenTheyMayNotSchedule_ThenTheRefusalLeavesNoEntry() {
    assertThatThrownBy(
            () ->
                service.defineShift(
                    admin(ACME),
                    new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false)))
        .isInstanceOf(NotPermittedException.class);

    assertThat(audit.changes()).isEmpty();
  }

  /**
   * An empty draft: generation runs before any shift exists, so there is nothing for it to fill and
   * whatever the draft carries afterwards was put there by hand.
   */
  private Roster emptyDraft(OrganisationId organisation) {
    return service.generateDraft(
        scheduler(organisation), new GenerateRosterCommand(organisation, THE_WEEK));
  }

  private EmployeeId qualified(
      OrganisationId organisation, String name, Set<QualificationId> holds) {
    EmployeeId id = EmployeeId.newId();
    workforce.add(
        organisation, new RosterCandidate(id, name, holds, List.of(), StatedPreferences.none()));
    return id;
  }

  private ShiftId aShift(OrganisationId organisation, Set<QualificationId> required) {
    return service.defineShift(
        scheduler(organisation),
        new DefineShiftCommand(organisation, MONDAY_DAY, ZoneOffset.UTC, 1, required, false));
  }

  @Test
  void GivenADraftAndAShift_WhenASchedulerAssignsSomeone_ThenTheDraftCarriesThatAssignment() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());

    Roster amended =
        service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada));

    assertThat(amended.assignments()).containsExactly(new Assignment(shift, ada));
    assertThat(rosters.findById(ACME, draft.id()))
        .describedAs("the amended draft is stored, not only returned")
        .get()
        .extracting(Roster::assignments)
        .isEqualTo(List.of(new Assignment(shift, ada)));
  }

  @Test
  void GivenAnAssignment_WhenASchedulerRemovesIt_ThenTheDraftNoLongerCarriesIt() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());
    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada));

    Roster amended =
        service.unassign(
            scheduler(ACME), new RemoveAssignmentCommand(ACME, draft.id(), shift, ada));

    assertThat(amended.assignments()).isEmpty();
  }

  @Test
  void GivenAnAdminRole_WhenAssigning_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());

    assertThatThrownBy(
            () ->
                service.assign(admin(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada)))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnEmployeeRole_WhenAssigning_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());

    assertThatThrownBy(
            () ->
                service.assign(
                    new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE),
                    new AssignToShiftCommand(ACME, draft.id(), shift, ada)))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenASchedulerOfAnotherOrganisation_WhenAssigning_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(RIVAL), new AssignToShiftCommand(ACME, draft.id(), shift, ada)))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenNoSuchRoster_WhenAssigning_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    ShiftId shift = aShift(ACME, Set.of());

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, RosterId.newId(), shift, ada)))
        .isInstanceOf(UnknownRosterException.class);
  }

  @Test
  void GivenNoSuchShift_WhenAssigning_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(ACME),
                    new AssignToShiftCommand(ACME, draft.id(), ShiftId.newId(), ada)))
        .isInstanceOf(UnknownShiftException.class);
  }

  /**
   * Rule R5 on the manual path. Scheduling knows a person exists only because the published port
   * returned them, so somebody who does not come back is not assignable — and assigning them anyway
   * would put work on a roster whose eligibility nobody established.
   */
  @Test
  void GivenSomebodyFromAnotherOrganisation_WhenAssigningThem_ThenItIsRefused() {
    EmployeeId theirs = available(RIVAL, "Their Employee");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, theirs)))
        .isInstanceOf(UnknownAssigneeException.class);
  }

  /** Rule R4 on the manual path. */
  @Test
  void GivenAShiftDemandingAQualificationTheyLack_WhenAssigningThem_ThenItIsRefused() {
    QualificationId forklift = QualificationId.newId();
    workforce.define(ACME, forklift);
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of(forklift));

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada)))
        .isInstanceOf(AssigneeNotQualifiedException.class);
  }

  @Test
  void GivenAShiftDemandingAQualificationTheyHold_WhenAssigningThem_ThenItIsPermitted() {
    QualificationId forklift = QualificationId.newId();
    workforce.define(ACME, forklift);
    EmployeeId ada = qualified(ACME, "Ada", Set.of(forklift));
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of(forklift));

    assertThatCode(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada)))
        .doesNotThrowAnyException();
  }

  @Test
  void GivenSomebodyAlreadyOnTheShift_WhenAssigningThemAgain_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());
    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada));

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada)))
        .isInstanceOf(DuplicateAssignmentException.class);
  }

  /** Rule R6: a published roster is superseded, never edited. */
  @Test
  void GivenAPublishedRoster_WhenAssigningToIt_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    Roster published = service.publish(scheduler(ACME), ACME, draft.id());
    ShiftId shift = aShift(ACME, Set.of());

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, published.id(), shift, ada)))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenAnAssignmentThatWasNeverMade_WhenRemovingIt_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());

    assertThatThrownBy(
            () ->
                service.unassign(
                    scheduler(ACME), new RemoveAssignmentCommand(ACME, draft.id(), shift, ada)))
        .isInstanceOf(UnknownAssignmentException.class);
  }

  /**
   * The reason assignment does not check working-time rules itself. They judge a whole schedule, so
   * the honest place to enforce them is publication — which cannot be bypassed, and which refuses
   * hand-made work exactly as it refuses generated work.
   */
  @Test
  void GivenHandAssignedWorkBreakingADailyLimit_WhenPublishing_ThenItIsStillRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());
    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada));
    // Invented: four hours a day, which the eight-hour shift just assigned by hand cannot satisfy.
    service.reviseConstraints(
        admin(ACME),
        new ReviseConstraintsCommand(
            ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(4)))));

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, draft.id()))
        .isInstanceOf(RosterNotCompliantException.class);
  }

  /**
   * R36 at publication. Both shifts are on the 6th in Tokyo, twelve hours there, but counted in UTC
   * they fall seven hours on the 5th and five on the 6th, under the cap.
   */
  @Test
  void GivenTwoShiftsOnOneDayInTheirOwnZone_WhenPublishingUnderADailyCap_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ZoneId tokyo = ZoneId.of("Asia/Tokyo");
    for (TimeRange when :
        List.of(
            new TimeRange(
                Instant.parse("2026-10-05T15:00:00Z"), Instant.parse("2026-10-05T21:00:00Z")),
            new TimeRange(
                Instant.parse("2026-10-05T23:00:00Z"), Instant.parse("2026-10-06T05:00:00Z")))) {
      ShiftId shift =
          service.defineShift(
              scheduler(ACME), new DefineShiftCommand(ACME, when, tokyo, 1, Set.of(), false));
      service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada));
    }
    // Invented: ten hours a day.
    service.reviseConstraints(
        admin(ACME),
        new ReviseConstraintsCommand(
            ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(10)))));

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, draft.id()))
        .isInstanceOf(RosterNotCompliantException.class);
  }

  /**
   * The hole the review found. Generation filters candidates through {@code
   * RosterCandidate.couldCover}, which tests availability as well as qualifications, so it refuses
   * to roster somebody through their declared absence. Assigning by hand used to accept it, and
   * publication could not catch it afterwards: unavailability is not a working-time constraint, so
   * an empty constraint set checks nothing at all and the roster publishes with somebody working a
   * day they said they could not.
   */
  @Test
  void GivenSomebodyWhoDeclaredTheyCannotWorkThen_WhenAssigningThem_ThenItIsRefused() {
    EmployeeId busy = EmployeeId.newId();
    workforce.add(
        ACME,
        new RosterCandidate(busy, "Ada", Set.of(), List.of(MONDAY_DAY), StatedPreferences.none()));
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, busy)))
        .isInstanceOf(AssigneeUnavailableException.class);
  }

  @Test
  void GivenAbsenceThatDoesNotTouchTheShift_WhenAssigningThem_ThenItIsPermitted() {
    TimeRange elsewhere =
        new TimeRange(Instant.parse("2026-10-09T08:00:00Z"), Instant.parse("2026-10-09T16:00:00Z"));
    EmployeeId ada = EmployeeId.newId();
    workforce.add(
        ACME,
        new RosterCandidate(ada, "Ada", Set.of(), List.of(elsewhere), StatedPreferences.none()));
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());

    assertThatCode(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada)))
        .doesNotThrowAnyException();
  }

  /**
   * R11 depends on a roster's period describing the work it carries. Generation can only assign
   * shifts it found inside that period; assigning by hand could put a December shift on an October
   * roster, and then the roster that genuinely covers December would not supersede it — two
   * published rosters authoritative for one moment, which is what R11 exists to prevent.
   */
  @Test
  void GivenAShiftOutsideTheRostersPeriod_WhenAssigningIt_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    TimeRange december =
        new TimeRange(Instant.parse("2026-12-07T08:00:00Z"), Instant.parse("2026-12-07T16:00:00Z"));
    ShiftId elsewhere =
        service.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, december, ZoneOffset.UTC, 1, Set.of(), false));

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), elsewhere, ada)))
        .isInstanceOf(ShiftOutsideRosterPeriodException.class);
  }

  /**
   * Rule R3 on the manual path, with no rules configured. Generation never puts somebody on two
   * shifts at once, and the only working-time rule that noticed an overlap is optional, so an
   * organisation with an empty constraint set could publish one person in two places.
   */
  @Test
  void GivenSomebodyAlreadyOnAnOverlappingShift_WhenAssigningThem_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId day = aShift(ACME, Set.of());
    TimeRange afternoon =
        new TimeRange(Instant.parse("2026-10-05T12:00:00Z"), Instant.parse("2026-10-05T20:00:00Z"));
    ShiftId overlapping =
        service.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, afternoon, ZoneOffset.UTC, 1, Set.of(), false));
    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), day, ada));
    audit.forget();

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), overlapping, ada)))
        .isInstanceOf(OverlappingAssignmentException.class);

    assertThat(rosters.byId(draft.id()).assignments())
        .describedAs("the refused assignment is not stored")
        .containsExactly(new Assignment(day, ada));
    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenShiftsThatOnlyTouch_WhenAssigningOnePersonToBoth_ThenItIsPermitted() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId day = aShift(ACME, Set.of());
    TimeRange evening =
        new TimeRange(Instant.parse("2026-10-05T16:00:00Z"), Instant.parse("2026-10-05T20:00:00Z"));
    ShiftId next =
        service.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, evening, ZoneOffset.UTC, 1, Set.of(), false));
    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), day, ada));

    assertThatCode(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), next, ada)))
        .describedAs("a shift ending as the next begins is not two places at once")
        .doesNotThrowAnyException();
  }

  /**
   * The gate nothing bypasses. A draft can carry overlapping work without passing through {@code
   * assign} — a generator is a port, and this one is not the greedy adapter — and an empty
   * constraint set checks nothing, so publication has to refuse it for itself.
   */
  @Test
  void GivenADraftAlreadyCarryingOverlappingWork_WhenPublishingWithNoRules_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    ShiftId day = aShift(ACME, Set.of());
    ShiftId sameDay = aShift(ACME, Set.of());
    Roster draft =
        new Roster(RosterId.newId(), ACME, THE_WEEK)
            .withAssignments(List.of(new Assignment(day, ada), new Assignment(sameDay, ada)));
    rosters.save(draft);

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, draft.id()))
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations())
                    .extracting(ConstraintViolation::employee, ConstraintViolation::constraint)
                    .containsExactly(tuple(ada, ConstraintViolation.OVERLAPPING_WORK)));

    assertThat(rosters.byId(draft.id()).status()).isEqualTo(RosterStatus.DRAFT);
    assertThat(audit.changes()).doesNotContain(SchedulingChange.ROSTER_PUBLISHED);
  }

  // --- #205: publication refuses every rule a draft breaks, and lists each (ADR-042) ------------

  /**
   * R18 at publication. Assigning by hand refuses somebody through their declared absence, but a
   * draft can carry them anyway: they declared it after being assigned, or a generator put them
   * there. Publication, which nothing bypasses, refuses it for itself, naming the shift and never
   * the span they declared.
   */
  @Test
  void
      GivenADraftCarryingSomebodyDeclaredUnavailableThen_WhenPublishing_ThenItIsRefusedNamingThem() {
    EmployeeId ada = EmployeeId.newId();
    workforce.add(
        ACME,
        new RosterCandidate(ada, "Ada", Set.of(), List.of(MONDAY_DAY), StatedPreferences.none()));
    ShiftId day = aShift(ACME, Set.of());
    Roster draft = carrying(new Assignment(day, ada));

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, draft.id()))
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations())
                    .containsExactly(ConstraintViolation.declaredUnavailable(ada, day)));
    assertThat(rosters.byId(draft.id()).status()).isEqualTo(RosterStatus.DRAFT);
  }

  /** R4 at publication, for the same reason R18 is. */
  @Test
  void
      GivenADraftCarryingSomebodyWithoutTheShiftsQualification_WhenPublishing_ThenItIsRefusedNamingThem() {
    QualificationId forklift = QualificationId.newId();
    workforce.define(ACME, forklift);
    EmployeeId ada = available(ACME, "Ada");
    ShiftId day = aShift(ACME, Set.of(forklift));
    Roster draft = carrying(new Assignment(day, ada));

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, draft.id()))
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations())
                    .containsExactly(
                        ConstraintViolation.missingQualifications(ada, day, Set.of(forklift))));
  }

  /**
   * R5 at publication. Somebody the organisation's directory does not return cannot be shown
   * eligible for anything, so they are refused rather than skipped: a skipped check is work on a
   * roster whose eligibility nobody established.
   */
  @Test
  void
      GivenADraftCarryingSomebodyTheOrganisationDoesNotHave_WhenPublishing_ThenItIsRefusedNamingThem() {
    EmployeeId stranger = EmployeeId.newId();
    ShiftId day = aShift(ACME, Set.of());
    Roster draft = carrying(new Assignment(day, stranger));

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, draft.id()))
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations())
                    .containsExactly(ConstraintViolation.notOfTheOrganisation(stranger, day)));
  }

  /**
   * Every breach at once, not the first one found. A Scheduler fixing a draft is told everything
   * wrong with it in one answer, rather than one thing per attempt.
   */
  @Test
  void GivenADraftBreakingSeveralRulesAtOnce_WhenPublishing_ThenEveryViolationIsListed() {
    QualificationId forklift = QualificationId.newId();
    workforce.define(ACME, forklift);
    EmployeeId ada = available(ACME, "Ada");
    EmployeeId grace = EmployeeId.newId();
    workforce.add(
        ACME,
        new RosterCandidate(
            grace, "Grace", Set.of(), List.of(MONDAY_DAY), StatedPreferences.none()));
    ShiftId day = aShift(ACME, Set.of(forklift));
    ShiftId sameDay = aShift(ACME, Set.of());
    Roster draft =
        carrying(
            new Assignment(day, ada), new Assignment(sameDay, ada), new Assignment(sameDay, grace));

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, draft.id()))
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations())
                    .extracting(ConstraintViolation::employee, ConstraintViolation::constraint)
                    .containsExactlyInAnyOrder(
                        tuple(ada, ConstraintViolation.OVERLAPPING_WORK),
                        tuple(ada, ConstraintViolation.MISSING_QUALIFICATIONS),
                        tuple(grace, ConstraintViolation.DECLARED_UNAVAILABLE)));
  }

  // --- #205: checking a draft against every rule without publishing it (ADR-042) ---------------

  /**
   * The agreement #205 asks for: the check and publication are one implementation, so on the same
   * roster they name the same breaches. Every kind of rule is broken here - an overlap, a missing
   * qualification, a declared absence, somebody not of the organisation, and a working-time rule -
   * so a check that had drifted from publication on any of them would fail. Values are invented.
   */
  @Test
  void
      GivenADraftBreakingEveryKindOfRule_WhenAssessedAndThenPublished_ThenPublicationRefusesWithExactlyTheAssessedViolations() {
    QualificationId forklift = QualificationId.newId();
    workforce.define(ACME, forklift);
    EmployeeId ada = available(ACME, "Ada");
    EmployeeId grace = EmployeeId.newId();
    workforce.add(
        ACME,
        new RosterCandidate(
            grace, "Grace", Set.of(), List.of(MONDAY_DAY), StatedPreferences.none()));
    EmployeeId linus = available(ACME, "Linus");
    EmployeeId stranger = EmployeeId.newId();
    ShiftId day = aShift(ACME, Set.of(forklift));
    ShiftId sameDay = aShift(ACME, Set.of());
    // Invented: one hour a day, which Linus's eight-hour shift cannot satisfy.
    service.reviseConstraints(
        admin(ACME),
        new ReviseConstraintsCommand(
            ACME, List.of(new MaximumDailyWorkingTime(Duration.ofHours(1)))));
    Roster draft =
        carrying(
            new Assignment(day, ada),
            new Assignment(sameDay, ada),
            new Assignment(sameDay, grace),
            new Assignment(sameDay, linus),
            new Assignment(sameDay, stranger));

    RosterAssessment assessed = service.findAssessment(scheduler(ACME), ACME, draft.id());

    assertThat(assessed.violations())
        .extracting(ConstraintViolation::constraint)
        .contains(
            ConstraintViolation.OVERLAPPING_WORK,
            ConstraintViolation.MISSING_QUALIFICATIONS,
            ConstraintViolation.DECLARED_UNAVAILABLE,
            ConstraintViolation.NOT_OF_THE_ORGANISATION,
            "maximum daily working time");
    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, draft.id()))
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations()).containsExactlyElementsOf(assessed.violations()));
  }

  /**
   * Advisory: it decides nothing, so it holds nothing. Taking the publication lock would make every
   * check wait behind publications for an answer that is stale once it returns anyway, and a read
   * for update would be refused in the read-only transaction a {@code find} use case runs in.
   */
  @Test
  void GivenADraft_WhenAssessed_ThenNoPublicationIsLockedAndNothingIsHeld() {
    Roster draft = emptyDraft(ACME);
    rosters.log().forget();

    service.findAssessment(scheduler(ACME), ACME, draft.id());

    assertThat(rosters.log().calls())
        .doesNotContain(CallLog.Call.LOCK_PUBLICATIONS, CallLog.Call.FIND_ROSTER_FOR_UPDATE);
  }

  @Test
  void GivenADraftBreakingARule_WhenAssessed_ThenNothingIsStoredOrRecorded() {
    EmployeeId stranger = EmployeeId.newId();
    Roster draft = carrying(new Assignment(aShift(ACME, Set.of()), stranger));
    audit.forget();

    service.findAssessment(scheduler(ACME), ACME, draft.id());

    assertThat(rosters.byId(draft.id())).isEqualTo(draft);
    assertThat(rosters.byId(draft.id()).status()).isEqualTo(RosterStatus.DRAFT);
    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenAShiftShortOfPeople_WhenAssessed_ThenItsShortfallIsListed() {
    ShiftId needingTwo =
        service.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 2, Set.of(), false));
    Roster draft = carrying(new Assignment(needingTwo, available(ACME, "Ada")));

    RosterAssessment assessed = service.findAssessment(scheduler(ACME), ACME, draft.id());

    assertThat(assessed.violations()).isEmpty();
    assertThat(assessed.shortfalls()).containsExactly(new Shortfall(needingTwo, 2, 1));
  }

  /**
   * A shift straddling into next week may be carried by either roster (R35). Whether staffing it on
   * the adjacent roster counts towards it here is still open (#130, #135), so only this draft's own
   * assignments are counted.
   */
  @Test
  void GivenAShiftStraddlingIntoTheNextRoster_WhenAssessed_ThenOnlyThisDraftsAssignmentsCount() {
    EmployeeId ada = available(ACME, "Ada");
    ShiftId sundayNight = shiftAt(SUNDAY_NIGHT);
    Roster thisWeek = storedDraft(THE_WEEK);
    Roster nextWeek = storedDraft(NEXT_WEEK);
    assign(nextWeek, sundayNight, ada);

    RosterAssessment assessed = service.findAssessment(scheduler(ACME), ACME, thisWeek.id());

    assertThat(assessed.shortfalls()).containsExactly(new Shortfall(sundayNight, 1, 0));
  }

  // --- F34, ADR-010: the draft check scores the draft. Every weight here is invented. -----------

  /**
   * Ada would rather not work Monday, and the draft puts her on Monday's shift, which is marked
   * unpopular: one unpopular shift for her, and one preference gone against. Disruption is not
   * weighed, so it adds nothing to the total.
   */
  @Test
  void GivenWeightedObjectives_WhenAssessed_ThenEachMeasureAndTheWeightedTotalAreReported() {
    EmployeeId ada = EmployeeId.newId();
    workforce.add(
        ACME,
        new RosterCandidate(
            ada,
            "Ada",
            Set.of(),
            List.of(),
            new StatedPreferences(List.of(), List.of(MONDAY_DAY))));
    ShiftId unpopular =
        service.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), true));
    service.reviseObjectives(
        admin(ACME),
        new ReviseObjectivesCommand(ACME, Map.of(Objective.FAIRNESS, 3, Objective.PREFERENCES, 2)));
    Roster draft = carrying(new Assignment(unpopular, ada));

    RosterScore score = service.findAssessment(scheduler(ACME), ACME, draft.id()).score();

    assertThat(score.measures()).isEqualTo(new RosterMeasures(1, 1, 0));
    assertThat(score.weightedTotal()).isEqualTo(3 * 1 + 2 * 1);
  }

  /** Weighing nothing prefers nothing: still measured, and every draft totals zero. */
  @Test
  void GivenNothingWeighted_WhenAssessed_ThenTheDraftIsMeasuredButTotalsZero() {
    ShiftId unpopular =
        service.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), true));
    Roster draft = carrying(new Assignment(unpopular, available(ACME, "Ada")));

    RosterScore score = service.findAssessment(scheduler(ACME), ACME, draft.id()).score();

    assertThat(score.measures().heaviestUnpopularLoad()).isEqualTo(1);
    assertThat(score.weightedTotal()).isZero();
  }

  /** The baseline is what publishing the draft would supersede, as publication decides it (R11). */
  @Test
  void GivenADraftReplacingAPublishedRoster_WhenAssessed_ThenTheAssignmentsItChangesAreCounted() {
    available(ACME, "Ada");
    EmployeeId bea = available(ACME, "Bea");
    ShiftId monday = aShift(ACME, Set.of());
    Roster published = publishTheWeek();
    Roster replacement = carrying(new Assignment(monday, bea));

    RosterScore score = service.findAssessment(scheduler(ACME), ACME, replacement.id()).score();

    assertThat(published.assignments()).doesNotContain(new Assignment(monday, bea));
    assertThat(score.measures().assignmentsChanged())
        .describedAs("whoever had Monday loses it, and Bea gains it")
        .isEqualTo(2);
  }

  /** The score never decides: a draft breaking a rule is still scored, and still refused. */
  @Test
  void GivenADraftBreakingARule_WhenAssessed_ThenItIsStillScored() {
    Roster draft = carrying(new Assignment(aShift(ACME, Set.of()), EmployeeId.newId()));

    RosterAssessment assessed = service.findAssessment(scheduler(ACME), ACME, draft.id());

    assertThat(assessed.violations()).isNotEmpty();
    assertThat(assessed.score().measures()).isEqualTo(new RosterMeasures(0, 0, 0));
  }

  @Test
  void GivenAnAdmin_WhenAssessingADraft_ThenItIsAnswered() {
    Roster draft = emptyDraft(ACME);

    assertThat(service.findAssessment(admin(ACME), ACME, draft.id()).roster())
        .isEqualTo(draft.id());
  }

  /** A draft is personal data about every colleague on it (R12). */
  @Test
  void GivenAnEmployee_WhenAssessingADraft_ThenItIsRefused() {
    Roster draft = emptyDraft(ACME);

    assertThatThrownBy(
            () ->
                service.findAssessment(
                    new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE), ACME, draft.id()))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenASchedulerOfAnotherOrganisation_WhenAssessingOurDraft_ThenItIsRefused() {
    Roster draft = emptyDraft(ACME);

    assertThatThrownBy(() -> service.findAssessment(scheduler(RIVAL), ACME, draft.id()))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenTheOperator_WhenAssessingADraft_ThenItIsRefused() {
    Roster draft = emptyDraft(ACME);

    assertThatThrownBy(() -> service.findAssessment(new Operator(UserId.newId()), ACME, draft.id()))
        .isInstanceOf(NotATenantMemberException.class);
  }

  /** A published roster was judged under the rules version it records (Q9), not today's. */
  @Test
  void GivenAPublishedRoster_WhenAssessed_ThenItIsRefusedAsNotADraft() {
    Roster draft = emptyDraft(ACME);
    service.publish(scheduler(ACME), ACME, draft.id());

    assertThatThrownBy(() -> service.findAssessment(scheduler(ACME), ACME, draft.id()))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenNoSuchRoster_WhenAssessed_ThenItIsUnknown() {
    assertThatThrownBy(() -> service.findAssessment(scheduler(ACME), ACME, RosterId.newId()))
        .isInstanceOf(UnknownRosterException.class);
  }

  /** A draft stored as given, bypassing the checks assigning by hand would make. */
  private Roster carrying(Assignment... assignments) {
    Roster draft =
        new Roster(RosterId.newId(), ACME, THE_WEEK).withAssignments(List.of(assignments));
    rosters.save(draft);
    return draft;
  }

  /**
   * Publishing decides what to supersede from what it reads, so the organisation's publications
   * have to be held before that, or a publication in flight is missed (ADR-027). The first thing
   * publication reads is the draft itself, so a lock taken any later than this is caught here.
   */
  @Test
  void GivenAScheduler_WhenPublishing_ThenTheOrganisationsPublicationsWaitBeforeAnyRosterIsRead() {
    Roster draft = emptyDraft(ACME);
    rosters.log().forget();

    service.publish(scheduler(ACME), ACME, draft.id());

    assertThat(rosters.log().calls()).first().isEqualTo(CallLog.Call.LOCK_PUBLICATIONS);
  }

  /**
   * Publication stores the roster it judged, so it holds that roster from the moment it reads it:
   * an edit landing in between would otherwise be stored over (#33, ADR-041). The draft is read
   * under the publication lock, never before it, so a publication waiting on an edit holds nothing
   * another publication needs.
   */
  @Test
  void GivenAScheduler_WhenPublishing_ThenTheDraftIsReadForUpdateUnderThePublicationLock() {
    Roster draft = emptyDraft(ACME);
    rosters.log().forget();

    service.publish(scheduler(ACME), ACME, draft.id());

    assertThat(rosters.log().calls())
        .startsWith(CallLog.Call.LOCK_PUBLICATIONS, CallLog.Call.FIND_ROSTER_FOR_UPDATE);
  }

  /**
   * #33: a draft is stored whole, so an edit holds it from its read, and a second edit of the same
   * draft waits and then reads the first one's change rather than storing over it. That the read
   * really waits is proven against PostgreSQL, in {@code JdbcRosterRepositoryTest}.
   */
  @Test
  void GivenADraft_WhenASchedulerAssigns_ThenTheDraftIsReadForUpdate() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());
    rosters.log().forget();

    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada));

    assertThat(rosters.log().calls()).first().isEqualTo(CallLog.Call.FIND_ROSTER_FOR_UPDATE);
  }

  /** As {@link #GivenADraft_WhenASchedulerAssigns_ThenTheDraftIsReadForUpdate}, for removing. */
  @Test
  void GivenADraft_WhenASchedulerRemovesAnAssignment_ThenTheDraftIsReadForUpdate() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());
    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada));
    rosters.log().forget();

    service.unassign(scheduler(ACME), new RemoveAssignmentCommand(ACME, draft.id(), shift, ada));

    assertThat(rosters.log().calls()).containsExactly(CallLog.Call.FIND_ROSTER_FOR_UPDATE);
  }

  /**
   * Reading a roster changes nothing, so it holds nothing: it runs read-only, where a read for
   * update would be refused, and holding there would only make edits wait on a reader.
   */
  @Test
  void GivenARoster_WhenRead_ThenItIsNotHeld() {
    Roster draft = emptyDraft(ACME);
    rosters.log().forget();

    service.findRoster(scheduler(ACME), ACME, draft.id());

    assertThat(rosters.log().calls()).containsExactly(CallLog.Call.FIND_ROSTER);
  }

  // --- R35: R3 is judged across every authoritative roster, not within one --------------------

  private Roster storedDraft(TimeRange period) {
    Roster draft = new Roster(RosterId.newId(), ACME, period);
    rosters.save(draft);
    return draft;
  }

  private ShiftId shiftAt(TimeRange when) {
    return service.defineShift(
        scheduler(ACME), new DefineShiftCommand(ACME, when, ZoneOffset.UTC, 1, Set.of(), false));
  }

  private void assign(Roster draft, ShiftId shift, EmployeeId who) {
    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, who));
  }

  private static Roster publishedCarrying(TimeRange period, Assignment assignment) {
    return new Roster(RosterId.newId(), ACME, period)
        .withAssignments(List.of(assignment))
        .publishedUnder(1, NOW.minus(Duration.ofDays(1)));
  }

  @Test
  void GivenTheirWorkOnTheAdjacentPublishedRoster_WhenAssigningOverlappingWork_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster thisWeek = storedDraft(THE_WEEK);
    assign(thisWeek, shiftAt(SUNDAY_NIGHT), ada);
    service.publish(scheduler(ACME), ACME, thisWeek.id());
    Roster nextWeek = storedDraft(NEXT_WEEK);
    ShiftId early = shiftAt(NEXT_MONDAY_EARLY);
    audit.forget();

    assertThatThrownBy(() -> assign(nextWeek, early, ada))
        .isInstanceOf(OverlappingAssignmentException.class)
        .hasMessageContaining(thisWeek.id().value().toString());

    assertThat(rosters.byId(nextWeek.id()).assignments()).isEmpty();
    assertThat(audit.changes()).isEmpty();
  }

  /**
   * The guarantee. Assigning checks without taking the publication lock, so publication checks
   * again under it (ADR-027, ADR-033). Both drafts here were filled before either was published, so
   * only publication can see the clash.
   */
  @Test
  void GivenTwoDraftsPuttingOnePersonInTwoPlaces_WhenTheSecondIsPublished_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    Roster thisWeek = storedDraft(THE_WEEK);
    assign(thisWeek, shiftAt(SUNDAY_NIGHT), ada);
    Roster nextWeek = storedDraft(NEXT_WEEK);
    assign(nextWeek, shiftAt(NEXT_MONDAY_EARLY), ada);
    service.publish(scheduler(ACME), ACME, nextWeek.id());
    audit.forget();

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, thisWeek.id()))
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations())
                    .singleElement()
                    .satisfies(
                        overlap -> {
                          assertThat(overlap.constraint())
                              .isEqualTo(ConstraintViolation.OVERLAPPING_WORK);
                          assertThat(overlap.detail()).contains(nextWeek.id().value().toString());
                        }));

    assertThat(rosters.byId(thisWeek.id()).status()).isEqualTo(RosterStatus.DRAFT);
    assertThat(rosters.byId(nextWeek.id()).status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(audit.changes()).isEmpty();
  }

  /**
   * Publishing supersedes the roster it overlaps, whole (R11, Q16). That roster's work stops
   * binding anybody the moment this one lands, so it is no evidence against it.
   */
  @Test
  void
      GivenOverlappingWorkOnlyOnTheRosterBeingReplaced_WhenAssigningAndPublishing_ThenBothSucceed() {
    EmployeeId ada = available(ACME, "Ada");
    publishedWork(ACME, ada, MONDAY_DAY);
    Roster replacement = storedDraft(THE_WEEK);
    TimeRange afternoon =
        new TimeRange(Instant.parse("2026-10-05T12:00:00Z"), Instant.parse("2026-10-05T20:00:00Z"));
    ShiftId instead = shiftAt(afternoon);

    assertThatCode(() -> assign(replacement, instead, ada)).doesNotThrowAnyException();
    assertThat(service.publish(scheduler(ACME), ACME, replacement.id()).status())
        .isEqualTo(RosterStatus.PUBLISHED);
  }

  /**
   * Refuse rather than skip: work on another roster that cannot be placed in time cannot be shown
   * not to overlap.
   */
  @Test
  void GivenTheirWorkOnTheAdjacentRosterCannotBeResolved_WhenPublishing_ThenItIsNotVerifiable() {
    EmployeeId ada = available(ACME, "Ada");
    rosters.save(publishedCarrying(NEXT_WEEK, new Assignment(ShiftId.newId(), ada)));
    Roster thisWeek =
        new Roster(RosterId.newId(), ACME, THE_WEEK)
            .withAssignments(List.of(new Assignment(aShift(ACME, Set.of()), ada)));
    rosters.save(thisWeek);

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, thisWeek.id()))
        .isInstanceOf(RosterNotVerifiableException.class);

    assertThat(rosters.byId(thisWeek.id()).status()).isEqualTo(RosterStatus.DRAFT);
  }

  /**
   * A roster that is not a draft is refused before the work beside it is looked up. That lookup
   * refuses a shift it cannot resolve as not verifiable, and the answer owed here is that this
   * roster cannot be published again at all (#133).
   */
  @Test
  void
      GivenAPublishedRosterBesideWorkThatCannotBeResolved_WhenPublishingItAgain_ThenItIsRefusedAsNotEditable() {
    EmployeeId ada = available(ACME, "Ada");
    Roster thisWeek = storedDraft(THE_WEEK);
    assign(thisWeek, shiftAt(MONDAY_DAY), ada);
    service.publish(scheduler(ACME), ACME, thisWeek.id());
    rosters.save(publishedCarrying(NEXT_WEEK, new Assignment(ShiftId.newId(), ada)));

    assertThatThrownBy(() -> service.publish(scheduler(ACME), ACME, thisWeek.id()))
        .isInstanceOf(RosterNotEditableException.class);
  }

  /**
   * Only the people on this roster are judged. A colleague's unresolvable work could sit on any
   * roster the organisation ever published, and refusing for it would block every later publication
   * with nothing on this draft to fix.
   */
  @Test
  void GivenAColleaguesUnresolvableWorkOnTheAdjacentRoster_WhenPublishing_ThenItIsPublished() {
    EmployeeId ada = available(ACME, "Ada");
    rosters.save(publishedCarrying(NEXT_WEEK, new Assignment(ShiftId.newId(), EmployeeId.newId())));
    Roster thisWeek = storedDraft(THE_WEEK);
    assign(thisWeek, aShift(ACME, Set.of()), ada);

    assertThat(service.publish(scheduler(ACME), ACME, thisWeek.id()).status())
        .isEqualTo(RosterStatus.PUBLISHED);
  }

  /**
   * The main path, which counting one assignment twice would break. Generation re-covers a shift
   * running past the end of the week it was published in, and carries over whoever that week put on
   * it (#130), so next week's roster carries Ada on Sunday night too. Either roster may carry that
   * shift (R35), and it is one place to be, not two (ADR-033).
   */
  @Test
  void GivenAShiftStraddlingIntoNextWeek_WhenNextWeekIsGeneratedAndPublished_ThenItIsPublished() {
    EmployeeId ada = available(ACME, "Ada");
    ShiftId sundayNight = shiftAt(SUNDAY_NIGHT);
    Roster thisWeek =
        service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    service.publish(scheduler(ACME), ACME, thisWeek.id());

    Roster nextWeek =
        service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, NEXT_WEEK));

    assertThat(nextWeek.assignments()).containsExactly(new Assignment(sundayNight, ada));
    assertThat(service.publish(scheduler(ACME), ACME, nextWeek.id()).status())
        .isEqualTo(RosterStatus.PUBLISHED);
    assertThat(rosters.byId(thisWeek.id()).status())
        .describedAs("rosters that only touch do not supersede each other")
        .isEqualTo(RosterStatus.PUBLISHED);
  }

  /**
   * #130: what a generator is asked carries over whoever the published neighbour puts on a shift
   * this draft covers too, and holds the rest of their work there as time they are busy.
   */
  @Test
  void GivenAStraddlingShiftStaffedOnThePublishedNeighbour_WhenGenerating_ThenTheyAreCarriedOver() {
    available(ACME, "Ada");
    EmployeeId grace = available(ACME, "Grace");
    Roster thisWeek = storedDraft(THE_WEEK);
    ShiftId sundayNight = shiftAt(SUNDAY_NIGHT);
    ShiftId sundayEvening =
        shiftAt(
            new TimeRange(
                Instant.parse("2026-10-11T16:00:00Z"), Instant.parse("2026-10-11T20:00:00Z")));
    assign(thisWeek, sundayNight, grace);
    assign(thisWeek, sundayEvening, grace);
    service.publish(scheduler(ACME), ACME, thisWeek.id());
    List<GenerationRequest> asked = new ArrayList<>();

    Roster nextWeek =
        generatingWith(
                request -> {
                  asked.add(request);
                  return List.of();
                })
            .generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, NEXT_WEEK));

    assertThat(nextWeek.assignments()).containsExactly(new Assignment(sundayNight, grace));
    assertThat(asked)
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.draft().assignments())
                  .containsExactly(new Assignment(sundayNight, grace));
              assertThat(request.heldElsewhere())
                  .extracting(HeldWork::shift, HeldWork::employee, HeldWork::roster)
                  .containsExactlyInAnyOrder(
                      tuple(sundayNight, grace, thisWeek.id()),
                      tuple(sundayEvening, grace, thisWeek.id()));
            });
  }

  /**
   * Somebody carried over who can no longer legally stay refuses generation, naming the breach,
   * before a generator is asked anything and before anything is stored or recorded. Leaving them
   * off instead would have the two rosters disagree about who works the shift.
   */
  @Test
  void GivenSomebodyCarriedOverWhoHasSinceDeclaredThemselvesAway_WhenGenerating_ThenItIsRefused() {
    EmployeeId grace = available(ACME, "Grace");
    Roster thisWeek = storedDraft(THE_WEEK);
    ShiftId sundayNight = shiftAt(SUNDAY_NIGHT);
    assign(thisWeek, sundayNight, grace);
    service.publish(scheduler(ACME), ACME, thisWeek.id());
    workforce.declareUnavailable(ACME, grace, NEXT_MONDAY_EARLY);
    audit.forget();
    List<GenerationRequest> asked = new ArrayList<>();

    assertThatThrownBy(
            () ->
                generatingWith(
                        request -> {
                          asked.add(request);
                          return List.of();
                        })
                    .generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, NEXT_WEEK)))
        .isInstanceOfSatisfying(
            RosterNotCompliantException.class,
            refused ->
                assertThat(refused.violations())
                    .containsExactly(ConstraintViolation.declaredUnavailable(grace, sundayNight)));

    assertThat(asked).isEmpty();
    assertThat(rosters.findByOrganisation(ACME)).containsExactly(rosters.byId(thisWeek.id()));
    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenAScheduler_WhenAssigningAndRemoving_ThenBothAreRecordedAgainstThem() {
    EmployeeId ada = available(ACME, "Ada");
    TenantActor scheduler = scheduler(ACME);
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());

    service.assign(scheduler, new AssignToShiftCommand(ACME, draft.id(), shift, ada));
    service.unassign(scheduler, new RemoveAssignmentCommand(ACME, draft.id(), shift, ada));

    assertThat(audit.of(SchedulingChange.ASSIGNMENT_ADDED))
        .containsExactly(
            new AuditedChange(
                ACME, scheduler.id(), SchedulingChange.ASSIGNMENT_ADDED, draft.id().value()));
    assertThat(audit.of(SchedulingChange.ASSIGNMENT_REMOVED))
        .containsExactly(
            new AuditedChange(
                ACME, scheduler.id(), SchedulingChange.ASSIGNMENT_REMOVED, draft.id().value()));
  }

  @Test
  void GivenAnUnqualifiedAssignee_WhenTheAssignmentIsRefused_ThenNothingIsRecorded() {
    QualificationId forklift = QualificationId.newId();
    workforce.define(ACME, forklift);
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of(forklift));

    assertThatThrownBy(
            () ->
                service.assign(
                    scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada)))
        .isInstanceOf(AssigneeNotQualifiedException.class);

    assertThat(audit.changes()).doesNotContain(SchedulingChange.ASSIGNMENT_ADDED);
  }

  private static TenantActor themself(OrganisationId organisation, EmployeeId employee) {
    return new TenantActor(UserId.newId(), organisation, Role.EMPLOYEE, Optional.of(employee));
  }

  /** Publishes a roster carrying exactly one assignment, and returns the shift it covers. */
  private ShiftId publishedWork(OrganisationId organisation, EmployeeId employee, TimeRange when) {
    Roster draft = emptyDraft(organisation);
    ShiftId shift =
        service.defineShift(
            scheduler(organisation),
            new DefineShiftCommand(organisation, when, ZoneOffset.UTC, 1, Set.of(), false));
    service.assign(
        scheduler(organisation),
        new AssignToShiftCommand(organisation, draft.id(), shift, employee));
    service.publish(scheduler(organisation), organisation, draft.id());
    return shift;
  }

  @Test
  void GivenPublishedWork_WhenTheyReadTheirOwnSchedule_ThenTheySeeItWithItsTimes() {
    EmployeeId ada = available(ACME, "Ada");
    ShiftId shift = publishedWork(ACME, ada, MONDAY_DAY);

    List<RosteredShift> mine = service.findOwnSchedule(themself(ACME, ada), ACME, THE_WEEK);

    assertThat(mine).hasSize(1);
    assertThat(mine.getFirst().shift()).isEqualTo(shift);
    assertThat(mine.getFirst().period()).isEqualTo(MONDAY_DAY);
  }

  /**
   * The disclosure this capability exists to avoid. Reading a whole roster stays an Admin and
   * Scheduler act (R12); this answers only for the person asking.
   */
  @Test
  void GivenAColleagueIsAlsoRostered_WhenReadingTheirOwnSchedule_ThenTheColleagueIsNotInIt() {
    EmployeeId ada = available(ACME, "Ada");
    EmployeeId grace = available(ACME, "Grace");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());
    ShiftId other =
        service.defineShift(
            scheduler(ACME),
            new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false));
    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada));
    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), other, grace));
    service.publish(scheduler(ACME), ACME, draft.id());

    List<RosteredShift> mine = service.findOwnSchedule(themself(ACME, ada), ACME, THE_WEEK);

    assertThat(mine).extracting(RosteredShift::shift).containsExactly(shift);
  }

  /**
   * A draft binds nobody and may never be published; showing one invites planning around fiction.
   */
  @Test
  void GivenTheirWorkIsOnlyADraft_WhenReadingTheirOwnSchedule_ThenTheyAreShownNothing() {
    EmployeeId ada = available(ACME, "Ada");
    Roster draft = emptyDraft(ACME);
    ShiftId shift = aShift(ACME, Set.of());
    service.assign(scheduler(ACME), new AssignToShiftCommand(ACME, draft.id(), shift, ada));

    assertThat(service.findOwnSchedule(themself(ACME, ada), ACME, THE_WEEK)).isEmpty();
  }

  /**
   * Exactly one roster is authoritative for any moment (R11), and it is not the superseded one.
   *
   * <p>Publishing the second roster supersedes the first, and generation re-covers the shift the
   * first one already carried — so that shift is assigned on both rosters. It must still appear
   * once. Counting the superseded roster would show somebody the same day of work twice and imply
   * they owe two of them.
   *
   * <p>The second roster's own shift is on another day, because a roster putting one person on two
   * overlapping shifts cannot be published (R3).
   */
  @Test
  void GivenTheirRosterWasSuperseded_WhenReadingTheirOwnSchedule_ThenItIsNotCountedAgain() {
    EmployeeId ada = available(ACME, "Ada");
    ShiftId onBothRosters = publishedWork(ACME, ada, MONDAY_DAY);
    TimeRange wednesday =
        new TimeRange(Instant.parse("2026-10-07T08:00:00Z"), Instant.parse("2026-10-07T16:00:00Z"));
    publishedWork(ACME, ada, wednesday);

    List<RosteredShift> mine = service.findOwnSchedule(themself(ACME, ada), ACME, THE_WEEK);

    assertThat(mine).extracting(RosteredShift::shift).doesNotHaveDuplicates();
    assertThat(mine).extracting(RosteredShift::shift).containsOnlyOnce(onBothRosters);
    assertThat(mine)
        .describedAs("every entry comes from the one roster that is still authoritative")
        .hasSize(2)
        .extracting(RosteredShift::roster)
        .containsOnly(mine.getFirst().roster());
  }

  @Test
  void GivenWorkOutsideTheSpanAsked_WhenReadingTheirOwnSchedule_ThenItIsNotReturned() {
    EmployeeId ada = available(ACME, "Ada");
    publishedWork(ACME, ada, MONDAY_DAY);
    TimeRange nextMonth =
        new TimeRange(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-08T00:00:00Z"));

    assertThat(service.findOwnSchedule(themself(ACME, ada), ACME, nextMonth)).isEmpty();
  }

  @Test
  void GivenWorkOnSeveralDays_WhenReadingTheirOwnSchedule_ThenItComesBackEarliestFirst() {
    EmployeeId ada = available(ACME, "Ada");
    TimeRange wednesday =
        new TimeRange(Instant.parse("2026-10-07T08:00:00Z"), Instant.parse("2026-10-07T16:00:00Z"));
    ShiftId later = publishedWork(ACME, ada, wednesday);
    ShiftId earlier = publishedWork(ACME, ada, MONDAY_DAY);

    List<RosteredShift> mine = service.findOwnSchedule(themself(ACME, ada), ACME, THE_WEEK);

    assertThat(mine).extracting(RosteredShift::shift).containsExactly(earlier, later);
  }

  /**
   * A user is not an employee (ADR-012). Refusing beats an empty list: "you have no personnel
   * record" and "you are rostered for nothing" are different answers, and only one needs fixing.
   */
  @Test
  void GivenAnActorWithNoPersonnelRecord_WhenReadingTheirOwnSchedule_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.findOwnSchedule(
                    new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE), ACME, THE_WEEK))
        .isInstanceOf(NoEmployeeRecordException.class);
  }

  /**
   * Still refused, and now refused sooner and for a better reason: not "you have no personnel
   * record" but "you are not a member of this organisation at all" (ADR-030).
   */
  @Test
  void GivenTheOperator_WhenReadingTheirOwnSchedule_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findOwnSchedule(new Operator(UserId.newId()), ACME, THE_WEEK))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenReadingOurSchedules_ThenItIsRefused() {
    EmployeeId ada = available(ACME, "Ada");
    publishedWork(ACME, ada, MONDAY_DAY);

    assertThatThrownBy(() -> service.findOwnSchedule(themself(RIVAL, ada), ACME, THE_WEEK))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenSomebodyReadsTheirOwnSchedule_WhenItIsAnswered_ThenNothingIsRecorded() {
    EmployeeId ada = available(ACME, "Ada");
    publishedWork(ACME, ada, MONDAY_DAY);
    audit.forget();

    service.findOwnSchedule(themself(ACME, ada), ACME, THE_WEEK);

    assertThat(audit.changes())
        .describedAs("reads are not audited, or the writes would be buried under them")
        .isEmpty();
  }

  // --- ADR-030: the platform operator is refused by every tenant use case -------------------
  //
  // One test per driving-port method rather than a table. A parameterised version would read as a
  // list and hide which row is the one whose failure is a security incident; here every one of them
  // is, so each gets its own name and its own line in a failure report.

  @Test
  void GivenTheOperator_WhenDefiningAShift_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.defineShift(
                    anOperator(),
                    new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false)))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenListingShifts_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findShifts(anOperator(), ACME, THE_WEEK))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenReadingAShift_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findShift(anOperator(), ACME, ShiftId.newId()))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenReadingTheConstraintSet_ThenItIsRefused() {
    assertThatThrownBy(() -> service.currentConstraints(anOperator(), ACME))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenReadingAnEarlierConstraintVersion_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findConstraints(anOperator(), ACME, 1))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenRevisingTheConstraintSet_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.reviseConstraints(
                    anOperator(), new ReviseConstraintsCommand(ACME, List.of())))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenGeneratingADraft_ThenItIsRefused() {
    assertThatThrownBy(
            () -> service.generateDraft(anOperator(), new GenerateRosterCommand(ACME, THE_WEEK)))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenAssigningSomebodyToAShift_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.assign(
                    anOperator(),
                    new AssignToShiftCommand(
                        ACME, RosterId.newId(), ShiftId.newId(), EmployeeId.newId())))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenRemovingAnAssignment_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.unassign(
                    anOperator(),
                    new RemoveAssignmentCommand(
                        ACME, RosterId.newId(), ShiftId.newId(), EmployeeId.newId())))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenPublishingARoster_ThenItIsRefused() {
    assertThatThrownBy(() -> service.publish(anOperator(), ACME, RosterId.newId()))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenListingRosters_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findRosters(anOperator(), ACME))
        .isInstanceOf(NotATenantMemberException.class);
  }

  private static Operator anOperator() {
    return new Operator(UserId.newId());
  }
}
