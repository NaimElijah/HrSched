package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How an organisation weights the objectives (R40, ADR-010).
 *
 * <p>Every weight here is invented, and none is a recommendation: an organisation chooses its own.
 */
class ObjectiveWeightsTest {

  private static final OrganisationId ACME = OrganisationId.newId();

  @Test
  void GivenAWeightOfOne_WhenWeighting_ThenItIsAccepted() {
    ObjectiveWeights weights = new ObjectiveWeights(ACME, Map.of(Objective.FAIRNESS, 1));

    assertThat(weights.weightOf(Objective.FAIRNESS)).contains(1);
  }

  /** Zero would say "off" a second way; leaving the objective out already says it. */
  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void GivenAWeightBelowOne_WhenWeighting_ThenItIsRefused(int weight) {
    assertThatThrownBy(() -> new ObjectiveWeights(ACME, Map.of(Objective.DISRUPTION, weight)))
        .isInstanceOf(InvalidObjectiveWeightException.class)
        .hasMessageContaining("disruption");
  }

  /** ADR-024: the refusal names the objective and repeats nothing that was sent. */
  @Test
  void GivenAWeightBelowOne_WhenRefused_ThenTheMessageRepeatsNoValue() {
    assertThatThrownBy(() -> new ObjectiveWeights(ACME, Map.of(Objective.FAIRNESS, -7)))
        .hasMessageNotContaining("-7")
        .hasMessageNotContaining("7");
  }

  @Test
  void GivenAnObjectiveWithANullWeight_WhenWeighting_ThenItIsRefused() {
    Map<Objective, Integer> weights = new HashMap<>();
    weights.put(Objective.PREFERENCES, null);

    assertThatThrownBy(() -> new ObjectiveWeights(ACME, weights))
        .isInstanceOf(InvalidObjectiveWeightException.class);
  }

  @Test
  void GivenAnObjectiveLeftOut_WhenAskedForItsWeight_ThenItIsNotWeighed() {
    ObjectiveWeights weights = new ObjectiveWeights(ACME, Map.of(Objective.FAIRNESS, 2));

    assertThat(weights.weightOf(Objective.PREFERENCES)).isEmpty();
  }

  @Test
  void GivenNoWeights_WhenAskedForEachWeight_ThenNoObjectiveIsWeighed() {
    ObjectiveWeights none = ObjectiveWeights.none(ACME);

    for (Objective objective : Objective.values()) {
      assertThat(none.weightOf(objective)).isEmpty();
    }
  }

  @Test
  void GivenAMapChangedAfterwards_WhenWeighted_ThenTheWeightsDoNotChange() {
    Map<Objective, Integer> given = new HashMap<>(Map.of(Objective.FAIRNESS, 2));
    ObjectiveWeights weights = new ObjectiveWeights(ACME, given);

    given.put(Objective.FAIRNESS, 9);

    assertThat(weights.weightOf(Objective.FAIRNESS)).contains(2);
  }

  /** In the objectives' own order, whatever order they were given in. */
  @Test
  void GivenWeightsInAnyOrder_WhenListed_ThenTheyFollowTheObjectivesOrder() {
    Map<Objective, Integer> given = new HashMap<>();
    given.put(Objective.DISRUPTION, 1);
    given.put(Objective.FAIRNESS, 3);

    assertThat(new ObjectiveWeights(ACME, given).weights().keySet())
        .containsExactly(Objective.FAIRNESS, Objective.DISRUPTION);
  }

  @Test
  void GivenNoOrganisation_WhenWeighting_ThenItIsRejected() {
    assertThatThrownBy(() -> new ObjectiveWeights(null, Map.of()))
        .isInstanceOf(NullPointerException.class);
  }
}
