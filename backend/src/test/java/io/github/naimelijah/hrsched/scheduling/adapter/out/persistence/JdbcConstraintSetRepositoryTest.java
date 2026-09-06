package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumConsecutiveWorkingDays;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumWeeklyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MinimumRestBetweenAssignments;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingTimeConstraint;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the constraint store promises, and the one thing it now promises that it did not.
 *
 * <p>Every version is kept, keyed by {@code (organisation, version)}, because a published roster
 * records the version that approved it. {@code findCurrent} reads the highest, so the behaviour
 * callers see is unchanged; what changed is that the earlier versions are still there.
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
   * {@code ConstraintSet} is a plain list and permits two constraints of one kind, so the store
   * must too. Keying the rows on the kind would have refused this, which would be a new domain rule
   * introduced as a storage detail.
   */
  @Test
  void GivenTwoConstraintsOfOneKind_WhenReadBack_ThenBothAreStillThere() {
    List<WorkingTimeConstraint> twice =
        List.of(
            new MaximumDailyWorkingTime(Duration.ofHours(10)),
            new MaximumDailyWorkingTime(Duration.ofHours(12)));
    repository.save(ConstraintSet.initial(ACME).revisedTo(twice));

    assertThat(repository.findCurrent(ACME))
        .get()
        .extracting(ConstraintSet::constraints)
        .isEqualTo(twice);
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

  /** Saving is an upsert on the version, so a retry cannot leave two rows claiming to be one. */
  @Test
  void GivenTheSameVersionSavedTwice_WhenReadBack_ThenItIsNotDuplicated() {
    ConstraintSet set =
        ConstraintSet.initial(ACME).revisedTo(List.of(new MaximumConsecutiveWorkingDays(4)));
    repository.save(set);
    repository.save(set);

    assertThat(storedVersions()).containsExactly(2);
    assertThat(repository.findCurrent(ACME))
        .get()
        .extracting(ConstraintSet::constraints)
        .isEqualTo(List.of(new MaximumConsecutiveWorkingDays(4)));
  }

  private List<Integer> storedVersions() {
    return PostgresDatabase.jdbcClient()
        .sql("SELECT version FROM constraint_set WHERE organisation_id = :id ORDER BY version")
        .param("id", ACME.value())
        .query(Integer.class)
        .list();
  }
}
