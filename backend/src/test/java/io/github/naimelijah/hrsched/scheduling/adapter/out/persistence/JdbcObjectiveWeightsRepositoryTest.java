package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.domain.model.Objective;
import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the objective weights store promises (ADR-010): one set per organisation, replaced whole,
 * and an unweighted objective read back as unweighted.
 *
 * <p><strong>Every weight below is invented.</strong> None is a recommendation; an organisation
 * chooses its own.
 */
class JdbcObjectiveWeightsRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();

  private final JdbcObjectiveWeightsRepository repository =
      new JdbcObjectiveWeightsRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
    PostgresDatabase.given(ACME, RIVAL);
  }

  @Test
  void GivenNothingWeighted_WhenReadingTheWeights_ThenNothingIsFound() {
    assertThat(repository.find(ACME)).isEmpty();
  }

  @Test
  void GivenEveryObjectiveWeighted_WhenReadBack_ThenEachWeightComesBack() {
    ObjectiveWeights weights =
        new ObjectiveWeights(
            ACME, Map.of(Objective.FAIRNESS, 3, Objective.PREFERENCES, 2, Objective.DISRUPTION, 5));

    repository.save(weights);

    assertThat(repository.find(ACME)).contains(weights);
  }

  /**
   * A null column is an unweighted objective. Read as a number it would be zero, which is a weight
   * the domain refuses, so the set would not even load.
   */
  @Test
  void GivenSomeObjectivesLeftOut_WhenReadBack_ThenTheyAreStillUnweighted() {
    repository.save(new ObjectiveWeights(ACME, Map.of(Objective.PREFERENCES, 4)));

    ObjectiveWeights read = repository.find(ACME).orElseThrow();

    assertThat(read.weightOf(Objective.PREFERENCES)).contains(4);
    assertThat(read.weightOf(Objective.FAIRNESS)).isEmpty();
    assertThat(read.weightOf(Objective.DISRUPTION)).isEmpty();
  }

  /** Revision replaces the set whole: a weight left out of the second is no longer weighed. */
  @Test
  void GivenWeightsRevised_WhenReadBack_ThenOnlyTheLatestSetIsHeld() {
    repository.save(
        new ObjectiveWeights(ACME, Map.of(Objective.FAIRNESS, 3, Objective.DISRUPTION, 1)));
    ObjectiveWeights revised = new ObjectiveWeights(ACME, Map.of(Objective.PREFERENCES, 2));

    repository.save(revised);

    assertThat(repository.find(ACME)).contains(revised);
  }

  @Test
  void GivenAnotherOrganisationsWeights_WhenReadBackByUs_ThenNothingIsFound() {
    repository.save(new ObjectiveWeights(RIVAL, Map.of(Objective.FAIRNESS, 3)));

    assertThat(repository.find(ACME)).isEmpty();
  }

  @Test
  void GivenTwoOrganisationsRevising_WhenEachIsReadBack_ThenNeitherTouchedTheOther() {
    ObjectiveWeights ours = new ObjectiveWeights(ACME, Map.of(Objective.FAIRNESS, 3));
    ObjectiveWeights theirs = new ObjectiveWeights(RIVAL, Map.of(Objective.DISRUPTION, 7));

    repository.save(ours);
    repository.save(theirs);

    assertThat(repository.find(ACME)).contains(ours);
    assertThat(repository.find(RIVAL)).contains(theirs);
  }

  /** The schema refuses what the domain refuses (R40), for a writer that skips the domain. */
  @Test
  void GivenAWeightOfZero_WhenStoredDirectly_ThenTheSchemaRefusesIt() {
    assertThatThrownBy(
            () ->
                PostgresDatabase.jdbcClient()
                    .sql(
                        """
                        INSERT INTO objective_weights (organisation_id, fairness)
                        VALUES (:id, 0)
                        """)
                    .param("id", ACME.value())
                    .update())
        .hasMessageContaining("objective_weights_fairness_check");
  }
}
