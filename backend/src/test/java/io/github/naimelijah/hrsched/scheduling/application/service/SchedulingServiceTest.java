package io.github.naimelijah.hrsched.scheduling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.application.port.in.DefineShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseConstraintsCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SchedulingChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRequiredQualificationException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

  private FakeShifts shifts;
  private FakeRosters rosters;
  private FakeConstraintSets constraintSets;
  private FakeWorkforce workforce;
  private RecordingAuditTrail audit;
  private SchedulingService service;

  @BeforeEach
  void setUp() {
    shifts = new FakeShifts();
    rosters = new FakeRosters();
    constraintSets = new FakeConstraintSets();
    workforce = new FakeWorkforce();
    audit = new RecordingAuditTrail();
    service =
        new SchedulingService(
            shifts, rosters, constraintSets, workforce, new FirstFitGenerator(), audit);
  }

  private static TenantActor scheduler(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.SCHEDULER);
  }

  private static TenantActor admin(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.ADMIN);
  }

  private EmployeeId available(OrganisationId organisation, String name) {
    EmployeeId id = EmployeeId.newId();
    workforce.add(organisation, new RosterCandidate(id, name, Set.of(), List.of()));
    return id;
  }

  @Test
  void GivenAScheduler_WhenGeneratingAndPublishing_ThenTheConstraintVersionIsRecorded() {
    available(ACME, "Ada");
    service.defineShift(scheduler(ACME), new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));

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
    service.defineShift(scheduler(ACME), new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));
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
    service.defineShift(scheduler(ACME), new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));
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
                    new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of(QualificationId.newId()))))
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
                    scheduler(ACME), new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of(forklift))))
        .doesNotThrowAnyException();
  }

  @Test
  void GivenASchedulerOfAnotherOrganisation_WhenGeneratingARoster_ThenItIsNotTheirs() {
    assertThatThrownBy(
            () ->
                service.generateDraft(scheduler(RIVAL), new GenerateRosterCommand(ACME, THE_WEEK)))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAPlatformOperator_WhenReadingAnotherOrganisationsRoster_ThenItIsPermitted() {
    available(ACME, "Ada");
    service.defineShift(scheduler(ACME), new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));
    Roster draft =
        service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));

    Actor operator = new Operator(UserId.newId());

    assertThat(service.findRoster(operator, ACME, draft.id())).isPresent();
  }

  /**
   * Assigns the first candidates who could cover each shift, checking nothing else.
   *
   * <p>Deliberately not the greedy adapter: this test is about what the service does with whatever
   * a generator returns, including a roster the current rules reject. {@code
   * GreedyRosterGeneratorTest} covers the real one.
   */
  @Test
  void GivenShiftsInsideAndOutsideASpan_WhenListingThem_ThenOnlyTheOverlappingOnesComeBack() {
    service.defineShift(scheduler(ACME), new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));
    TimeRange nextMonth =
        new TimeRange(Instant.parse("2026-11-09T08:00:00Z"), Instant.parse("2026-11-09T16:00:00Z"));
    service.defineShift(scheduler(ACME), new DefineShiftCommand(ACME, nextMonth, 1, Set.of()));

    assertThat(service.findShifts(scheduler(ACME), ACME, THE_WEEK))
        .extracting(Shift::period)
        .containsExactly(MONDAY_DAY);
  }

  @Test
  void GivenAnotherOrganisationsShifts_WhenListingThem_ThenNothingLeaks() {
    service.defineShift(scheduler(ACME), new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));

    assertThatThrownBy(() -> service.findShifts(scheduler(RIVAL), ACME, THE_WEEK))
        .isInstanceOf(CrossTenantAccessException.class);
    assertThat(service.findShifts(scheduler(RIVAL), RIVAL, THE_WEEK)).isEmpty();
  }

  @Test
  void GivenRostersOfTwoOrganisations_WhenListingThem_ThenOnlyTheCallersComeBack() {
    available(ACME, "Ada");
    service.defineShift(scheduler(ACME), new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));
    Roster ours = service.generateDraft(scheduler(ACME), new GenerateRosterCommand(ACME, THE_WEEK));
    service.generateDraft(scheduler(RIVAL), new GenerateRosterCommand(RIVAL, THE_WEEK));

    assertThat(service.findRosters(scheduler(ACME), ACME))
        .extracting(Roster::id)
        .containsExactly(ours.id());
  }

  private static final class FirstFitGenerator implements RosterGenerator {
    @Override
    public List<Assignment> generate(
        List<Shift> shifts, List<RosterCandidate> candidates, ConstraintSet constraints) {
      List<Assignment> assignments = new ArrayList<>();
      for (Shift shift : shifts) {
        candidates.stream()
            .filter(
                candidate -> candidate.couldCover(shift.period(), shift.requiredQualifications()))
            .limit(shift.requiredHeadcount())
            .forEach(candidate -> assignments.add(new Assignment(shift.id(), candidate.id())));
      }
      return List.copyOf(assignments);
    }
  }

  private static final class FakeShifts implements ShiftRepository {
    private final Map<ShiftId, Shift> stored = new LinkedHashMap<>();

    @Override
    public void save(Shift shift) {
      stored.put(shift.id(), shift);
    }

    @Override
    public Optional<Shift> findById(OrganisationId organisation, ShiftId id) {
      return Optional.ofNullable(stored.get(id))
          .filter(shift -> shift.organisation().equals(organisation));
    }

    @Override
    public List<Shift> findWithin(OrganisationId organisation, TimeRange within) {
      return stored.values().stream()
          .filter(shift -> shift.organisation().equals(organisation))
          .filter(shift -> shift.period().overlaps(within))
          .toList();
    }
  }

  private static final class FakeRosters implements RosterRepository {
    private final Map<RosterId, Roster> stored = new LinkedHashMap<>();

    @Override
    public void save(Roster roster) {
      stored.put(roster.id(), roster);
    }

    @Override
    public Optional<Roster> findById(OrganisationId organisation, RosterId id) {
      return Optional.ofNullable(stored.get(id))
          .filter(roster -> roster.organisation().equals(organisation));
    }

    @Override
    public List<Roster> findByOrganisation(OrganisationId organisation) {
      return stored.values().stream()
          .filter(roster -> roster.organisation().equals(organisation))
          .toList();
    }

    Roster byId(RosterId id) {
      return stored.get(id);
    }
  }

  private static final class FakeConstraintSets implements ConstraintSetRepository {
    private final Map<OrganisationId, ConstraintSet> stored = new HashMap<>();

    @Override
    public void save(ConstraintSet constraintSet) {
      stored.put(constraintSet.organisation(), constraintSet);
    }

    @Override
    public Optional<ConstraintSet> findCurrent(OrganisationId organisation) {
      return Optional.ofNullable(stored.get(organisation));
    }
  }

  private static final class FakeWorkforce implements WorkforceDirectory {
    private final Map<OrganisationId, List<RosterCandidate>> people = new HashMap<>();
    private final Map<OrganisationId, Set<QualificationId>> defined = new HashMap<>();

    void add(OrganisationId organisation, RosterCandidate candidate) {
      people.computeIfAbsent(organisation, key -> new ArrayList<>()).add(candidate);
    }

    void define(OrganisationId organisation, QualificationId qualification) {
      defined
          .computeIfAbsent(organisation, key -> new java.util.LinkedHashSet<>())
          .add(qualification);
    }

    @Override
    public List<RosterCandidate> candidatesFor(Actor actor, OrganisationId organisation) {
      actor.requireAccessTo(organisation);
      return people.getOrDefault(organisation, List.of());
    }

    @Override
    public Set<QualificationId> definedQualifications(Actor actor, OrganisationId organisation) {
      actor.requireAccessTo(organisation);
      return defined.getOrDefault(organisation, Set.of());
    }
  }

  @Test
  void GivenAScheduler_WhenDefiningAShift_ThenTheChangeIsRecordedAgainstThem() {
    TenantActor scheduler = scheduler(ACME);

    ShiftId shift =
        service.defineShift(scheduler, new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));

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

  @Test
  void GivenAScheduler_WhenPublishingARoster_ThenGenerationAndPublicationAreBothRecorded() {
    available(ACME, "Ada");
    TenantActor scheduler = scheduler(ACME);
    service.defineShift(scheduler, new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));
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
    service.defineShift(scheduler, new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));
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
    service.defineShift(scheduler, new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of()));
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
   * A refused act changed nothing, so it must leave no entry saying it did. An audit trail that
   * records attempts as though they were changes is worse than none: it is wrong.
   */
  @Test
  void GivenAnActorOfAnotherOrganisation_WhenTheyAreRefused_ThenNothingIsRecorded() {
    assertThatThrownBy(
            () ->
                service.defineShift(
                    scheduler(RIVAL), new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of())))
        .isInstanceOf(CrossTenantAccessException.class);

    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenAnAdmin_WhenTheyMayNotSchedule_ThenTheRefusalLeavesNoEntry() {
    assertThatThrownBy(
            () ->
                service.defineShift(
                    admin(ACME), new DefineShiftCommand(ACME, MONDAY_DAY, 1, Set.of())))
        .isInstanceOf(NotPermittedException.class);

    assertThat(audit.changes()).isEmpty();
  }

  /**
   * Records what the service says was changed.
   *
   * <p>Hand-written, like every other double here: the audit trail is our own port, and asking it
   * what it was told is how "was this recorded?" becomes a test rather than a review comment.
   */
  private static final class RecordingAuditTrail implements AuditTrail {

    private final List<AuditedChange> recorded = new ArrayList<>();

    @Override
    public void record(AuditedChange change) {
      recorded.add(change);
    }

    List<SchedulingChange> changes() {
      return recorded.stream().map(AuditedChange::change).toList();
    }

    List<AuditedChange> of(SchedulingChange change) {
      return recorded.stream().filter(entry -> entry.change() == change).toList();
    }
  }
}
