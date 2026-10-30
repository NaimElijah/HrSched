package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.STUCK_AFTER_SECONDS;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.awaitWaitingOnALock;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.backendServingThisTransaction;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.refusalsOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintsRevisedConcurrentlyException;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumConsecutiveWorkingDays;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumWeeklyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MinimumRestBetweenAssignments;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingTimeConstraint;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the constraint store promises.
 *
 * <p>Every version is kept, keyed by {@code (organisation, version)}, because a published roster
 * records the version that approved it. {@code findCurrent} reads the highest; {@code findVersion}
 * reads any one of them back, which is what makes that record worth keeping (Q9).
 *
 * <p><strong>Every number below is invented to make a case pass or fail.</strong> None is taken
 * from any jurisdiction, and none may be.
 */
class JdbcConstraintSetRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();

  private final JdbcConstraintSetRepository repository =
      new JdbcConstraintSetRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
    PostgresDatabase.given(ACME, RIVAL);
  }

  @Test
  void GivenNothingConfigured_WhenReadingTheCurrentSet_ThenNothingIsFound() {
    assertThat(repository.findCurrent(ACME)).isEmpty();
  }

  @Test
  void GivenASet_WhenReadBackByItsOwnOrganisation_ThenItIsFound() {
    repository.save(ConstraintSet.initial(ACME));

    assertThat(repository.findCurrent(ACME)).contains(ConstraintSet.initial(ACME));
  }

  @Test
  void GivenAnotherOrganisationsSet_WhenReadBackByUs_ThenNothingIsFound() {
    repository.save(ConstraintSet.initial(RIVAL));

    assertThat(repository.findCurrent(ACME)).isEmpty();
  }

  @Test
  void GivenAnEmptySet_WhenReadBack_ThenItIsStillAnEmptySetRatherThanAbsent() {
    repository.save(ConstraintSet.initial(ACME));

    assertThat(repository.findCurrent(ACME))
        .get()
        .extracting(ConstraintSet::constraints)
        .isEqualTo(List.of());
  }

  @Test
  void GivenARevisedSet_WhenReadBack_ThenTheCurrentVersionIsTheLatest() {
    ConstraintSet initial = ConstraintSet.initial(ACME);
    repository.save(initial);
    repository.save(initial.revisedTo(List.of(new MaximumConsecutiveWorkingDays(4))));

    assertThat(repository.findCurrent(ACME)).get().extracting(ConstraintSet::version).isEqualTo(2);
  }

  @Test
  void GivenEveryKindOfConstraint_WhenReadBack_ThenEachKeepsItsValue() {
    List<WorkingTimeConstraint> everyKind =
        List.of(
            new MinimumRestBetweenAssignments(Duration.ofHours(9)),
            new MaximumDailyWorkingTime(Duration.ofHours(10)),
            new MaximumWeeklyWorkingTime(Duration.ofHours(41)),
            new MaximumConsecutiveWorkingDays(6));
    repository.save(ConstraintSet.initial(ACME).revisedTo(everyKind));

    assertThat(repository.findCurrent(ACME))
        .get()
        .extracting(ConstraintSet::constraints)
        .isEqualTo(everyKind);
  }

  @Test
  void GivenConstraintsInAnOrder_WhenReadBack_ThenTheOrderIsTheOneTheyWereSavedIn() {
    List<WorkingTimeConstraint> ordered =
        List.of(
            new MaximumConsecutiveWorkingDays(6),
            new MinimumRestBetweenAssignments(Duration.ofHours(9)));
    repository.save(ConstraintSet.initial(ACME).revisedTo(ordered));

    assertThat(repository.findCurrent(ACME))
        .get()
        .extracting(ConstraintSet::constraints)
        .isEqualTo(ordered);
  }

  /**
   * R24: a set holds at most one constraint of each kind, which {@code ConstraintSet} now refuses
   * to be built otherwise, so no revision can store two. The schema still permits them (V1), so a
   * set holding two could only have been written past the domain. Read back, it is refused rather
   * than loaded as rules that are not what they say, and refused as a fault in stored data, not as
   * the caller's mistake: a loud failure naming the set and saying what the domain refused (#96),
   * whichever way it is read. It carries no cause, because Spring would route the domain's refusal
   * as its cause to the 400 handler; {@code RosteringHttpTest} pins that it is answered by none.
   */
  @Test
  void GivenTwoConstraintsOfOneKindStoredPastTheDomain_WhenReadBack_ThenItFailsAsStoredData() {
    PostgresDatabase.jdbcClient()
        .sql("INSERT INTO constraint_set (organisation_id, version) VALUES (:id, 2)")
        .param("id", ACME.value())
        .update();
    // Invented: two daily caps, of ten hours and of twelve.
    for (int ordinal = 0; ordinal < 2; ordinal++) {
      PostgresDatabase.jdbcClient()
          .sql(
              """
              INSERT INTO working_time_constraint
                  (organisation_id, version, ordinal, kind, duration_seconds, day_count)
              VALUES (:id, 2, :ordinal, 'MAXIMUM_DAILY_WORKING_TIME', :seconds, NULL)
              """)
          .param("id", ACME.value())
          .param("ordinal", ordinal)
          .param("seconds", Duration.ofHours(10 + 2L * ordinal).toSeconds())
          .update();
    }

    assertThatThrownBy(() -> repository.findCurrent(ACME))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ACME.value().toString())
        .hasMessageContaining("version 2")
        .hasMessageContaining("maximum daily working time is given twice")
        .hasNoCause();
    assertThatThrownBy(() -> repository.findVersion(ACME, 2))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasNoCause();
  }

  /**
   * The debt the port records: a published roster names the version that approved it, and that is
   * worthless if the version is overwritten. Revising no longer discards what came before.
   */
  @Test
  void GivenARevisedSet_WhenTheEarlierVersionIsSought_ThenItIsStillStored() {
    ConstraintSet first = ConstraintSet.initial(ACME);
    ConstraintSet second = first.revisedTo(List.of(new MaximumConsecutiveWorkingDays(4)));
    repository.save(first);
    repository.save(second);
    repository.save(second.revisedTo(List.of(new MaximumConsecutiveWorkingDays(5))));

    assertThat(storedVersions()).containsExactly(1, 2, 3);
  }

  /**
   * Q9: a published roster keeps the version that approved it, and changes are never retroactive.
   * Storing that version is worth something only if it reads back with the rules it had then, once
   * the organisation has moved on.
   */
  @Test
  void GivenARevisedSet_WhenAnEarlierVersionIsFound_ThenItCarriesTheRulesItHadThen() {
    ConstraintSet second =
        ConstraintSet.initial(ACME).revisedTo(List.of(new MaximumConsecutiveWorkingDays(4)));
    repository.save(second);
    repository.save(second.revisedTo(List.of(new MaximumConsecutiveWorkingDays(5))));

    assertThat(repository.findVersion(ACME, 2)).contains(second);
  }

  /**
   * A version that switched every rule off is a version, not a missing one: the roster it approved
   * was approved with nothing enforced, and saying so is the answer.
   */
  @Test
  void GivenAVersionEnforcingNothing_WhenFound_ThenItIsAnEmptySetRatherThanAbsent() {
    ConstraintSet second =
        ConstraintSet.initial(ACME).revisedTo(List.of(new MaximumConsecutiveWorkingDays(4)));
    ConstraintSet third = second.revisedTo(List.of());
    repository.save(second);
    repository.save(third);

    assertThat(repository.findVersion(ACME, 3)).contains(third);
  }

  @Test
  void GivenAnotherOrganisationsVersion_WhenFoundByUs_ThenNothingIsFound() {
    repository.save(
        ConstraintSet.initial(RIVAL).revisedTo(List.of(new MaximumConsecutiveWorkingDays(4))));

    assertThat(repository.findVersion(ACME, 2)).isEmpty();
  }

  /**
   * Every organisation's second version is called 2, so the rules a version carries must be scoped
   * to their organisation as surely as the version itself is. Only two organisations holding the
   * same number tell the two scopes apart; with different numbers, a rule query missing its
   * organisation would still find nothing of anybody else's.
   */
  @Test
  void GivenBothOrganisationsAtTheSameVersion_WhenWeFindIt_ThenItCarriesOnlyOurRules() {
    ConstraintSet ours = bothAtVersionTwo();

    assertThat(repository.findVersion(ACME, 2)).contains(ours);
  }

  @Test
  void GivenBothOrganisationsAtTheSameVersion_WhenWeReadTheCurrentSet_ThenItCarriesOnlyOurRules() {
    ConstraintSet ours = bothAtVersionTwo();

    assertThat(repository.findCurrent(ACME)).contains(ours);
  }

  /** Stores a version 2 for each organisation, with different rules, and returns ours. */
  private ConstraintSet bothAtVersionTwo() {
    ConstraintSet ours =
        ConstraintSet.initial(ACME).revisedTo(List.of(new MaximumConsecutiveWorkingDays(4)));
    repository.save(ours);
    repository.save(
        ConstraintSet.initial(RIVAL)
            .revisedTo(
                List.of(
                    new MaximumConsecutiveWorkingDays(5),
                    new MaximumDailyWorkingTime(Duration.ofHours(7)))));
    return ours;
  }

  @Test
  void GivenAVersionNeverStored_WhenFound_ThenNothingIsFound() {
    repository.save(
        ConstraintSet.initial(ACME).revisedTo(List.of(new MaximumConsecutiveWorkingDays(4))));

    assertThat(repository.findVersion(ACME, 3)).isEmpty();
  }

  /**
   * Two Admins revising at once both read the same current version and compute the same next one,
   * and exactly one of them is about to lose their revision. The store refuses the second rather
   * than absorbing it: silencing the conflict would let it overwrite the first's constraints while
   * its audit line claimed success. The refusal takes its transaction with it (ADR-023), and is a
   * conflict the caller can act on (#238).
   */
  @Test
  void GivenAVersionAlreadyStored_WhenSavedAgain_ThenItIsRefusedRatherThanOverwritten() {
    ConstraintSet set =
        ConstraintSet.initial(ACME).revisedTo(List.of(new MaximumConsecutiveWorkingDays(4)));
    repository.save(set);

    assertThatThrownBy(() -> repository.save(sameVersionDifferently(set)))
        .isInstanceOf(ConstraintsRevisedConcurrentlyException.class)
        .hasCauseInstanceOf(DuplicateKeyException.class);

    assertThat(storedVersions()).containsExactly(2);
  }

  /**
   * #238: the race itself, rather than one save after another. Both revisions read version one and
   * store version two; the second waits on the first's uncommitted row, and once the first commits
   * it is refused as a conflict the caller can act on, reloading the rules and revising again,
   * rather than with the store's own exception, which nothing translates and which answers 500.
   *
   * <p>The first commits only once the second is waiting on its row, so the test cannot pass by the
   * second simply arriving after the first had committed.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenTwoRevisionsOfOneVersion_WhenTheyRace_ThenTheLoserIsRefusedAsAConflict()
      throws Exception {
    ConstraintSet current = ConstraintSet.initial(ACME);
    // Invented: four days running for the winner, five for the loser, so the two are told apart.
    ConstraintSet winning = current.revisedTo(List.of(new MaximumConsecutiveWorkingDays(4)));
    ConstraintSet losing = current.revisedTo(List.of(new MaximumConsecutiveWorkingDays(5)));
    AtomicInteger loser = new AtomicInteger();

    List<Throwable> refusals =
        refusalsOf(
            waitForTheOther -> {
              repository.save(winning);
              waitForTheOther.run();
              awaitWaitingOnALock(loser.get());
            },
            waitForTheOther -> {
              loser.set(backendServingThisTransaction());
              waitForTheOther.run();
              repository.save(losing);
            });

    assertThat(refusals)
        .singleElement()
        .isInstanceOf(ConstraintsRevisedConcurrentlyException.class);
    assertThat(repository.findCurrent(ACME)).contains(winning);
  }

  /**
   * Only the primary key's violation is a lost race. Any other unique constraint on the table,
   * which none is today but a later migration may add, is broken by something "reload and revise
   * again" cannot cure, so its violation surfaces as the store reported it. The index is invented
   * for this test and lives only in a transaction that is rolled back, so no other test sees it.
   */
  @Test
  void GivenAnotherUniqueConstraintBroken_WhenSaving_ThenItIsNotTakenForARacingRevision() {
    TransactionTemplate transaction = new TransactionTemplate(PostgresDatabase.transactions());

    Throwable refused =
        transaction.execute(
            status -> {
              status.setRollbackOnly();
              PostgresDatabase.jdbcClient()
                  .sql(
                      "CREATE UNIQUE INDEX constraint_set_invented_one_per_organisation"
                          + " ON constraint_set (organisation_id)")
                  .update();
              ConstraintSet second =
                  ConstraintSet.initial(ACME)
                      .revisedTo(List.of(new MaximumConsecutiveWorkingDays(4)));
              repository.save(second);
              return catchThrowable(() -> repository.save(second.revisedTo(List.of())));
            });

    assertThat(refused)
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("constraint_set_invented_one_per_organisation");
  }

  /** A different set claiming a version already taken — the shape a lost update arrives in. */
  private static ConstraintSet sameVersionDifferently(ConstraintSet stored) {
    return new ConstraintSet(
        stored.organisation(), stored.version(), List.of(new MaximumConsecutiveWorkingDays(5)));
  }

  /**
   * A CHECK passes when its expression is NULL, not only when it is TRUE, so a constraint carrying
   * neither of its two possible values has to be refused explicitly. Left implicit, the row stores
   * and then makes the whole organisation's set unreadable: the mapper reads a SQL NULL back as
   * zero, and a zero-length working-time limit is one the domain refuses to construct.
   */
  @Test
  void GivenAConstraintCarryingNeitherValue_WhenStoredDirectly_ThenTheSchemaRefusesIt() {
    PostgresDatabase.jdbcClient()
        .sql("INSERT INTO constraint_set (organisation_id, version) VALUES (:id, 1)")
        .param("id", ACME.value())
        .update();

    assertThatThrownBy(
            () ->
                PostgresDatabase.jdbcClient()
                    .sql(
                        """
                        INSERT INTO working_time_constraint
                            (organisation_id, version, ordinal, kind, duration_seconds, day_count)
                        VALUES (:id, 1, 0, 'MAXIMUM_DAILY_WORKING_TIME', NULL, NULL)
                        """)
                    .param("id", ACME.value())
                    .update())
        .hasMessageContaining("working_time_constraint_carries_its_one_value");
  }

  @Test
  void GivenACountingConstraintCarryingNoCount_WhenStoredDirectly_ThenTheSchemaRefusesIt() {
    PostgresDatabase.jdbcClient()
        .sql("INSERT INTO constraint_set (organisation_id, version) VALUES (:id, 1)")
        .param("id", ACME.value())
        .update();

    assertThatThrownBy(
            () ->
                PostgresDatabase.jdbcClient()
                    .sql(
                        """
                        INSERT INTO working_time_constraint
                            (organisation_id, version, ordinal, kind, duration_seconds, day_count)
                        VALUES (:id, 1, 0, 'MAXIMUM_CONSECUTIVE_WORKING_DAYS', NULL, NULL)
                        """)
                    .param("id", ACME.value())
                    .update())
        .hasMessageContaining("working_time_constraint_carries_its_one_value");
  }

  private List<Integer> storedVersions() {
    return PostgresDatabase.jdbcClient()
        .sql("SELECT version FROM constraint_set WHERE organisation_id = :id ORDER BY version")
        .param("id", ACME.value())
        .query(Integer.class)
        .list();
  }
}
