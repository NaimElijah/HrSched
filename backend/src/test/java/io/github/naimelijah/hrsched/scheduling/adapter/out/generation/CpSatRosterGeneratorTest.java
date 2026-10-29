package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.LinearExpr;
import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationPolicy;
import io.github.naimelijah.hrsched.scheduling.domain.model.Objective;
import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Specification of the CP-SAT generator (ADR-044): every promise in {@link
 * RosterGeneratorContract}, the coverage it is measured by, and what it does that greedy cannot.
 *
 * <p>The model is checked from both sides against the domain, on seeded problems: the contract
 * checks that the domain accepts whatever the model answers, and this checks that the model accepts
 * whatever legal roster the domain does, so it is neither looser nor stricter than the rules.
 *
 * <p>Every constraint value below is invented to force a specific outcome and says nothing about
 * any jurisdiction.
 */
class CpSatRosterGeneratorTest extends RosterGeneratorContract {

  /** Invented: ample for every problem here to be solved to proven optimality. */
  private static final Duration LIMIT = Duration.ofSeconds(10);

  /** Invented: longer than both searches together, as the generator requires. */
  private static final GenerationPolicy OUTLASTING_BOTH_SEARCHES =
      new GenerationPolicy(LIMIT.multipliedBy(3));

  /** How many seeded problems each property is checked on. */
  private static final int SEEDS = 25;

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final CpSatRosterGenerator generator =
      new CpSatRosterGenerator(
          meters, new GenerationProperties(LIMIT, 1), OUTLASTING_BOTH_SEARCHES);

  @Override
  protected RosterGenerator generator() {
    return generator;
  }

  private DistributionSummary summary(String name) {
    return meters.get(name).summary();
  }

  /**
   * Greedy takes Ada for the morning, the first who fits, and then nobody else holds the ticket the
   * overlapping afternoon needs. Grace can take the morning instead, so both are covered.
   */
  @Test
  void GivenSomebodyGreedyUsesUpEarly_WhenGenerating_ThenTheShiftOnlyTheyCouldTakeIsCoveredToo() {
    QualificationId ticket = QualificationId.newId();
    Shift morning = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    Shift afternoon = shift("2026-10-05T12:00:00Z", "2026-10-05T20:00:00Z", 1, Set.of(ticket));
    RosterCandidate ada =
        new RosterCandidate(
            EmployeeId.newId(), "Ada", Set.of(ticket), List.of(), StatedPreferences.none());
    GenerationRequest problem =
        request(
            List.of(morning, afternoon),
            List.of(ada, anyone("Grace")),
            ConstraintSet.initial(ACME));

    assertThat(new GreedyRosterGenerator().generate(problem)).hasSize(1);
    assertThat(generator.generate(problem))
        .contains(new Assignment(afternoon.id(), ada.id()))
        .hasSize(2);
  }

  /** Invented: the instant a roster being replaced was published, before the week it covers. */
  private static final Instant PUBLISHED = Instant.parse("2026-10-01T09:00:00Z");

  private static Shift unpopular(String start, String end) {
    return new Shift(
        ShiftId.newId(),
        ACME,
        new TimeRange(Instant.parse(start), Instant.parse(end)),
        ZoneOffset.UTC,
        1,
        Set.of(),
        true);
  }

  private static ObjectiveWeights weighing(Objective objective, int weight) {
    return new ObjectiveWeights(ACME, Map.of(objective, weight));
  }

  /** Generation into an empty draft for the week, weighed as given. */
  private static GenerationRequest weighed(
      List<Shift> shifts,
      List<RosterCandidate> candidates,
      ObjectiveWeights weights,
      List<Roster> superseded) {
    return new GenerationRequest(
        emptyDraft(),
        shifts,
        candidates,
        ConstraintSet.initial(ACME),
        List.of(),
        weights,
        superseded);
  }

  /** Greedy gives Ada both nights, being first by name; spread, nobody carries more than one. */
  @Test
  void GivenFairnessWeighed_WhenGenerating_ThenTheUnpopularShiftsAreSpread() {
    Shift saturdayNight = unpopular("2026-10-10T22:00:00Z", "2026-10-11T06:00:00Z");
    Shift sundayNight = unpopular("2026-10-11T22:00:00Z", "2026-10-12T06:00:00Z");
    RosterCandidate ada = anyone("Ada");
    RosterCandidate grace = anyone("Grace");
    GenerationRequest problem =
        weighed(
            List.of(saturdayNight, sundayNight),
            List.of(ada, grace),
            weighing(Objective.FAIRNESS, 1),
            List.of());

    assertThat(new GreedyRosterGenerator().generate(problem))
        .extracting(Assignment::employee)
        .containsOnly(ada.id());
    assertThat(generator.generate(problem))
        .extracting(Assignment::employee)
        .containsExactlyInAnyOrder(ada.id(), grace.id());
  }

  /** Monday, Tuesday and Wednesday, eight hours each: three shifts nobody can tell apart. */
  private static List<Shift> threeDays() {
    return List.of(
        shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of()),
        shift("2026-10-06T08:00:00Z", "2026-10-06T16:00:00Z", 1, Set.of()),
        shift("2026-10-07T08:00:00Z", "2026-10-07T16:00:00Z", 1, Set.of()));
  }

  private static RosterCandidate stating(
      String name, List<TimeRange> wouldLikeToWork, List<TimeRange> wouldRatherNotWork) {
    return new RosterCandidate(
        EmployeeId.newId(),
        name,
        Set.of(),
        List.of(),
        new StatedPreferences(wouldLikeToWork, wouldRatherNotWork));
  }

  /**
   * Every shift has one person who does not mind it and three who would rather not work it. Of the
   * many rosters filling all three, exactly one goes against nobody, and greedy, taking Ada first,
   * is not it.
   */
  @Test
  void GivenPreferencesWeighed_WhenGenerating_ThenNobodyIsPutWhereTheyWouldRatherNotBe() {
    List<Shift> days = threeDays();
    List<TimeRange> all = days.stream().map(Shift::period).toList();
    RosterCandidate ada = stating("Ada", List.of(), all);
    RosterCandidate grace = stating("Grace", List.of(), List.of(all.get(1), all.get(2)));
    RosterCandidate alan = stating("Alan", List.of(), List.of(all.get(0), all.get(2)));
    RosterCandidate edsger = stating("Edsger", List.of(), List.of(all.get(0), all.get(1)));
    GenerationRequest problem =
        weighed(
            days, List.of(ada, grace, alan, edsger), weighing(Objective.PREFERENCES, 1), List.of());

    assertThat(scoreOf(problem, new GreedyRosterGenerator().generate(problem))).isPositive();
    assertThat(generator.generate(problem))
        .containsExactlyInAnyOrder(
            new Assignment(days.get(0).id(), grace.id()),
            new Assignment(days.get(1).id(), alan.id()),
            new Assignment(days.get(2).id(), edsger.id()));
  }

  /** Each shift is the one a different person asked for; only one roster grants every wish. */
  @Test
  void GivenPreferencesWeighed_WhenGenerating_ThenEachWhoAskedForAShiftGetsIt() {
    List<Shift> days = threeDays();
    RosterCandidate ada = anyone("Ada");
    RosterCandidate grace = stating("Grace", List.of(days.get(0).period()), List.of());
    RosterCandidate alan = stating("Alan", List.of(days.get(1).period()), List.of());
    RosterCandidate edsger = stating("Edsger", List.of(days.get(2).period()), List.of());
    GenerationRequest problem =
        weighed(
            days, List.of(ada, grace, alan, edsger), weighing(Objective.PREFERENCES, 1), List.of());

    assertThat(scoreOf(problem, new GreedyRosterGenerator().generate(problem))).isPositive();
    assertThat(generator.generate(problem))
        .containsExactlyInAnyOrder(
            new Assignment(days.get(0).id(), grace.id()),
            new Assignment(days.get(1).id(), alan.id()),
            new Assignment(days.get(2).id(), edsger.id()));
  }

  /** Regenerating a published week keeps whoever it had, rather than telling people afresh. */
  @Test
  void GivenDisruptionWeighed_WhenRegeneratingAPublishedWeek_ThenItKeepsItsPeople() {
    List<Shift> days = threeDays();
    RosterCandidate ada = anyone("Ada");
    RosterCandidate grace = anyone("Grace");
    RosterCandidate alan = anyone("Alan");
    RosterCandidate edsger = anyone("Edsger");
    List<Assignment> before =
        List.of(
            new Assignment(days.get(0).id(), edsger.id()),
            new Assignment(days.get(1).id(), grace.id()),
            new Assignment(days.get(2).id(), alan.id()));
    Roster published =
        new Roster(RosterId.newId(), ACME, THE_WEEK)
            .withAssignments(before)
            .publishedUnder(1, PUBLISHED);
    GenerationRequest problem =
        weighed(
            days,
            List.of(ada, grace, alan, edsger),
            weighing(Objective.DISRUPTION, 1),
            List.of(published));

    assertThat(scoreOf(problem, new GreedyRosterGenerator().generate(problem))).isPositive();
    assertThat(generator.generate(problem)).containsExactlyInAnyOrderElementsOf(before);
  }

  /**
   * F11 before ADR-010: however heavily a preference is weighed, a place somebody can legally fill
   * is filled. Objectives only rank rosters that fill as many places.
   */
  @Test
  void GivenOnlyOnePersonCanTakeAShiftTheyWouldRatherNotWork_WhenGenerating_ThenTheyStillTakeIt() {
    Shift monday = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    RosterCandidate ada =
        new RosterCandidate(
            EmployeeId.newId(),
            "Ada",
            Set.of(),
            List.of(),
            new StatedPreferences(List.of(), List.of(monday.period())));

    assertThat(
            generator.generate(
                weighed(
                    List.of(monday),
                    List.of(ada),
                    weighing(Objective.PREFERENCES, 1000),
                    List.of())))
        .containsExactly(new Assignment(monday.id(), ada.id()));
  }

  /**
   * The model's score is the domain's, for any roster at all, not only the one it would choose:
   * legal rosters from greedy over shuffled orders, and the solver's own, are each fixed in the
   * model and their score read back.
   */
  @Test
  void GivenSeededProblemsAndAnyLegalRoster_WhenScoredByTheModel_ThenTheScoreIsTheDomainsOwn() {
    for (long seed = 1; seed <= SEEDS; seed++) {
      GenerationRequest problem = GenerationInstances.instance(seed, 8, 14);
      List<List<Assignment>> rosters = new ArrayList<>();
      rosters.add(generator.generate(problem));
      for (int order = 0; order < 2; order++) {
        List<RosterCandidate> shuffled = new ArrayList<>(problem.candidates());
        Collections.shuffle(shuffled, new Random(seed * 17 + order));
        rosters.add(
            new GreedyRosterGenerator()
                .generate(
                    new GenerationRequest(
                        problem.draft(),
                        problem.shifts(),
                        shuffled,
                        problem.constraints(),
                        problem.heldElsewhere(),
                        problem.weights(),
                        problem.superseded())));
      }
      for (List<Assignment> roster : rosters) {
        RosterModel model = RosterModel.of(problem);
        LinearExpr score = model.score();
        model
            .choices()
            .forEach(
                (pair, chosen) -> model.model().addEquality(chosen, roster.contains(pair) ? 1 : 0));
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(LIMIT.toSeconds());

        assertThat(solver.solve(model.model()))
            .describedAs("seed %d", seed)
            .isIn(CpSolverStatus.OPTIMAL, CpSolverStatus.FEASIBLE);
        assertThat(solver.value(score))
            .describedAs("seed %d", seed)
            .isEqualTo(scoreOf(problem, roster));
      }
    }
  }

  /** On problems nobody wrote by hand, it fills at least as many places as greedy does. */
  @Test
  void GivenSeededProblems_WhenGenerating_ThenNoFewerPlacesAreFilledThanGreedyFills() {
    for (long seed = 1; seed <= SEEDS; seed++) {
      GenerationRequest problem = GenerationInstances.instance(seed, 8, 14);

      assertThat(generator.generate(problem).size())
          .describedAs("seed %d", seed)
          .isGreaterThanOrEqualTo(new GreedyRosterGenerator().generate(problem).size());
    }
  }

  /**
   * The model is no stricter than the rules: every roster the domain accepts, the model can be made
   * to answer. Greedy over candidates in shuffled orders gives many different legal rosters, and
   * each is fixed in the model and solved.
   */
  @Test
  void GivenRostersTheDomainAccepts_WhenFixedInTheModel_ThenTheModelAcceptsThemToo() {
    for (long seed = 1; seed <= SEEDS; seed++) {
      GenerationRequest problem = GenerationInstances.instance(seed, 8, 14);
      for (int order = 0; order < 3; order++) {
        List<RosterCandidate> shuffled = new ArrayList<>(problem.candidates());
        Collections.shuffle(shuffled, new Random(seed * 31 + order));
        List<Assignment> legal =
            new GreedyRosterGenerator()
                .generate(
                    new GenerationRequest(
                        problem.draft(),
                        problem.shifts(),
                        shuffled,
                        problem.constraints(),
                        problem.heldElsewhere(),
                        problem.weights(),
                        problem.superseded()));
        assertThat(breachesOf(problem, legal)).describedAs("seed %d", seed).isEmpty();

        RosterModel model = RosterModel.of(problem);
        assertThat(model.choices().keySet())
            .describedAs("seed %d: a legal assignment the model never offers", seed)
            .containsAll(legal);
        model
            .choices()
            .forEach(
                (pair, chosen) -> model.model().addEquality(chosen, legal.contains(pair) ? 1 : 0));
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(LIMIT.toSeconds());

        assertThat(solver.solve(model.model()))
            .describedAs("seed %d: a legal roster the model refuses", seed)
            .isIn(CpSolverStatus.OPTIMAL, CpSolverStatus.FEASIBLE);
      }
    }
  }

  /**
   * ADR-045: a solve must hold no connection, so asking for one inside a transaction is a mistake
   * in the caller, refused before anything else.
   */
  @Test
  void GivenATransactionAlreadyOpen_WhenGenerating_ThenItIsRefused() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(
              () ->
                  generator.generate(
                      request(List.of(), List.of(anyone("Ada")), ConstraintSet.initial(ACME))))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void GivenAShiftNobodyCanCover_WhenGenerating_ThenTheGapIsMeasuredRatherThanOnlyLeft() {
    generator.generate(
        request(
            List.of(shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 3, Set.of())),
            List.of(),
            ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(3.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isEqualTo(3.0);
  }

  @Test
  void GivenAFullyCoveredShift_WhenGenerating_ThenNoGapIsRecorded() {
    generator.generate(
        request(
            List.of(shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 1, Set.of())),
            List.of(anyone("Ada")),
            ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(1.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isZero();
  }

  @Test
  void GivenAPartlyCoveredShift_WhenGenerating_ThenOnlyTheUnfilledPlacesCount() {
    generator.generate(
        request(
            List.of(shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 3, Set.of())),
            List.of(anyone("Ada")),
            ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(3.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isEqualTo(2.0);
  }

  /** People carried over fill their places, so those places are not gaps. */
  @Test
  void GivenAShiftFilledByPeopleCarriedOver_WhenGenerating_ThenNoGapIsRecorded() {
    Shift monday = shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 1, Set.of());
    RosterCandidate grace = anyone("Grace");

    generator.generate(carrying(monday, grace, List.of(grace), ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(1.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isZero();
  }

  /**
   * A generation asked for nothing is a different event from no generation at all, and a metric
   * that only appears on failure cannot show a recovery.
   */
  @Test
  void GivenNoShiftsAtAll_WhenGenerating_ThenTheGenerationIsStillCounted() {
    generator.generate(request(List.of(), List.of(anyone("Ada")), ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").count()).isEqualTo(1L);
    assertThat(summary("hrsched.shifts.uncovered").count()).isEqualTo(1L);
  }

  @Test
  void GivenNoTimeLimit_WhenConfigured_ThenItIsRefused() {
    assertThatThrownBy(() -> new GenerationProperties(Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GenerationProperties(null, 1))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * ADR-046: a generation still pending at its lost-at instant reads as lost, and the Scheduler is
   * told to start again. A policy that both searches could outlast would say so of a solve still
   * searching, so the generator, and with it startup, refuses it.
   */
  @Test
  void GivenALostAfterBothSearchesCouldOutlast_WhenTheGeneratorIsMade_ThenItIsRefused() {
    GenerationProperties limited = new GenerationProperties(LIMIT, 1);

    assertThatThrownBy(
            () ->
                new CpSatRosterGenerator(
                    meters, limited, new GenerationPolicy(LIMIT.multipliedBy(2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hrsched.generation.lost-after");
  }

  /**
   * The line every generation logs says how it went in counts, statuses and the organisation's
   * identity, and nothing about anybody: a candidate's name, or when they cannot work, is personal
   * data, and a log is copied to places a database is not. The name and the absence are invented.
   */
  @Test
  void GivenCandidatesWithNamesAndAbsences_WhenGenerated_ThenTheLogLineCarriesNeitherOfThem() {
    String name = "Ada Lovelace";
    TimeRange away =
        new TimeRange(Instant.parse("2026-10-06T00:00:00Z"), Instant.parse("2026-10-07T00:00:00Z"));
    RosterCandidate ada =
        new RosterCandidate(
            EmployeeId.newId(), name, Set.of(), List.of(away), StatedPreferences.none());
    Logger logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(CpSatRosterGenerator.class.getName());
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
    try {
      generator.generate(
          request(
              List.of(shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of())),
              List.of(ada),
              ConstraintSet.initial(ACME)));
    } finally {
      logger.detachAppender(captured);
    }

    assertThat(captured.list)
        .singleElement()
        .satisfies(
            logged -> {
              assertThat(logged.getLevel()).isEqualTo(Level.INFO);
              assertThat(logged.getFormattedMessage()).isEqualTo("Roster generated");
              assertThat(logged.getKeyValuePairs())
                  .extracting(pair -> pair.value)
                  .allSatisfy(
                      value ->
                          assertThat(value)
                              .describedAs("a count, a status or an identity, never free text")
                              .isInstanceOfAny(
                                  UUID.class, Integer.class, Double.class, CpSolverStatus.class));
            });
  }
}
