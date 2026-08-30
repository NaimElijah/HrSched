package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tenant keying and the version behaviour this adapter promises.
 *
 * <p>Unlike the other adapters this one is keyed by organisation rather than by an identity of its
 * own, so isolation is the map key rather than a filter. It also keeps only the current version,
 * which the class itself admits is an incompleteness rather than a decision: a published roster
 * records the version that approved it, and once history matters that version has to be
 * retrievable. The test below pins the present behaviour so replacing it is a visible change.
 *
 * <p>The threshold used here is invented to exercise storage, and is not taken from any statute.
 */
class InMemoryConstraintSetRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();

  private final InMemoryConstraintSetRepository repository = new InMemoryConstraintSetRepository();

  @Test
  void GivenNoRulesConfigured_WhenReadingTheCurrentSet_ThenNothingIsFound() {
    assertThat(repository.findCurrent(ACME)).isEmpty();
  }

  @Test
  void GivenARuleSet_WhenReadBackByItsOwnOrganisation_ThenItIsFound() {
    ConstraintSet initial = ConstraintSet.initial(ACME);
    repository.save(initial);

    assertThat(repository.findCurrent(ACME)).contains(initial);
  }

  @Test
  void GivenAnotherOrganisationsRules_WhenReadingOurs_ThenNothingIsFound() {
    repository.save(ConstraintSet.initial(RIVAL));

    assertThat(repository.findCurrent(ACME)).isEmpty();
  }

  @Test
  void GivenARevisedSet_WhenReadBack_ThenOnlyTheCurrentVersionIsHeld() {
    ConstraintSet initial = ConstraintSet.initial(ACME);
    repository.save(initial);
    repository.save(
        initial.revisedTo(List.of(new MaximumDailyWorkingTime(Duration.ofMinutes(600)))));

    assertThat(repository.findCurrent(ACME)).get().extracting(ConstraintSet::version).isEqualTo(2);
  }

  @Test
  void GivenTwoOrganisationsConfiguringRules_WhenReadingEach_ThenNeitherSeesTheOther() {
    repository.save(
        ConstraintSet.initial(ACME)
            .revisedTo(List.of(new MaximumDailyWorkingTime(Duration.ofMinutes(600)))));
    repository.save(ConstraintSet.initial(RIVAL));

    assertThat(repository.findCurrent(ACME)).get().extracting(ConstraintSet::version).isEqualTo(2);
    assertThat(repository.findCurrent(RIVAL)).get().extracting(ConstraintSet::version).isEqualTo(1);
  }
}
