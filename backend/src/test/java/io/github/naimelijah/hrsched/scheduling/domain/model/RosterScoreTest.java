package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What measures come to under one organisation's weights (ADR-010).
 *
 * <p>Every weight and measure here is invented; none is a recommendation.
 */
class RosterScoreTest {

  private static final OrganisationId ACME = OrganisationId.newId();

  @Test
  void GivenEveryObjectiveWeighted_WhenTotalled_ThenEachMeasureCountsByItsWeight() {
    ObjectiveWeights weights =
        new ObjectiveWeights(
            ACME, Map.of(Objective.FAIRNESS, 3, Objective.PREFERENCES, 2, Objective.DISRUPTION, 5));

    assertThat(new RosterScore(new RosterMeasures(1, 2, 3), weights).weightedTotal())
        .isEqualTo(3 * 1 + 2 * 2 + 5 * 3);
  }

  @Test
  void GivenAnObjectiveNotWeighed_WhenTotalled_ThenItAddsNothing() {
    ObjectiveWeights fairnessOnly = new ObjectiveWeights(ACME, Map.of(Objective.FAIRNESS, 4));

    assertThat(new RosterScore(new RosterMeasures(2, 50, 70), fairnessOnly).weightedTotal())
        .isEqualTo(8);
  }

  /** Weighing nothing prefers nothing, which is how rosters were judged before objectives. */
  @Test
  void GivenNothingWeighed_WhenTotalled_ThenEveryRosterScoresZero() {
    assertThat(
            new RosterScore(new RosterMeasures(9, 9, 9), ObjectiveWeights.none(ACME))
                .weightedTotal())
        .isZero();
  }

  /**
   * Two legal rosters compared under one weighting: an organisation caring three times as much
   * about fairness as about disruption prefers the roster that spreads nights, even though it moves
   * more people.
   */
  @Test
  void GivenTwoRostersUnderOneWeighting_WhenCompared_ThenTheLowerTotalIsTheBetterRoster() {
    ObjectiveWeights weights =
        new ObjectiveWeights(ACME, Map.of(Objective.FAIRNESS, 3, Objective.DISRUPTION, 1));
    RosterScore lopsided = new RosterScore(new RosterMeasures(4, 0, 0), weights);
    RosterScore spread = new RosterScore(new RosterMeasures(2, 0, 4), weights);

    assertThat(spread.weightedTotal()).isLessThan(lopsided.weightedTotal());
  }

  /** The total is a long, so the heaviest weight an organisation may choose still fits in it. */
  @Test
  void GivenTheHeaviestWeight_WhenTotalled_ThenTheTotalIsExact() {
    ObjectiveWeights heaviest =
        new ObjectiveWeights(ACME, Map.of(Objective.FAIRNESS, Integer.MAX_VALUE));

    assertThat(new RosterScore(new RosterMeasures(500, 0, 0), heaviest).weightedTotal())
        .isEqualTo(500L * Integer.MAX_VALUE);
  }

  /** A total too large for a long is refused, never wrapped round into a total that ranks wrong. */
  @Test
  void GivenATotalBeyondALong_WhenTotalled_ThenItIsRefusedRatherThanWrapped() {
    ObjectiveWeights heaviest =
        new ObjectiveWeights(
            ACME,
            Map.of(
                Objective.FAIRNESS,
                Integer.MAX_VALUE,
                Objective.PREFERENCES,
                Integer.MAX_VALUE,
                Objective.DISRUPTION,
                Integer.MAX_VALUE));
    RosterMeasures largest =
        new RosterMeasures(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    assertThatThrownBy(() -> new RosterScore(largest, heaviest).weightedTotal())
        .isInstanceOf(ArithmeticException.class);
  }
}
