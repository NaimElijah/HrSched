package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ObjectiveWeightsRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Objective;
import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing each organisation's objective weights in PostgreSQL, one row each.
 *
 * <p>A save replaces the row whole. Two Admins revising at once therefore take turns on it, and the
 * later revision is what stays: each says every weight that applies from now on, so neither can
 * leave half of the other's behind. The organisation is the key, so a save can only ever touch the
 * row of the organisation it names.
 */
@Repository
public class JdbcObjectiveWeightsRepository implements ObjectiveWeightsRepository {

  private final JdbcClient jdbc;

  /**
   * @param jdbc the client every statement of this repository runs through; must not be null
   * @throws NullPointerException if {@code jdbc} is null
   */
  public JdbcObjectiveWeightsRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(ObjectiveWeights weights) {
    jdbc.sql(
            """
            INSERT INTO objective_weights (organisation_id, fairness, preferences, disruption)
            VALUES (:organisation, :fairness, :preferences, :disruption)
            ON CONFLICT (organisation_id) DO UPDATE SET fairness = EXCLUDED.fairness,
                                                        preferences = EXCLUDED.preferences,
                                                        disruption = EXCLUDED.disruption
            """)
        .param("organisation", weights.organisation().value())
        .param("fairness", weights.weightOf(Objective.FAIRNESS).orElse(null))
        .param("preferences", weights.weightOf(Objective.PREFERENCES).orElse(null))
        .param("disruption", weights.weightOf(Objective.DISRUPTION).orElse(null))
        .update();
  }

  @Override
  public Optional<ObjectiveWeights> find(OrganisationId organisation) {
    return jdbc.sql(
            """
            SELECT organisation_id, fairness, preferences, disruption FROM objective_weights
            WHERE organisation_id = :organisation
            """)
        .param("organisation", organisation.value())
        .query((row, rowNumber) -> toWeights(organisation, row))
        .optional();
  }

  private static ObjectiveWeights toWeights(OrganisationId organisation, ResultSet row)
      throws SQLException {
    Map<Objective, Integer> weights = new EnumMap<>(Objective.class);
    for (Objective objective : Objective.values()) {
      // getObject rather than getInt, which reads a null column as zero: an unweighted objective
      // must come back unweighted, not as a weight the schema itself refuses.
      Integer weight = row.getObject(columnOf(objective), Integer.class);
      if (weight != null) {
        weights.put(objective, weight);
      }
    }
    return new ObjectiveWeights(organisation, weights);
  }

  /** Exhaustive, so a new objective does not compile until it has somewhere to be stored. */
  private static String columnOf(Objective objective) {
    return switch (objective) {
      case FAIRNESS -> "fairness";
      case PREFERENCES -> "preferences";
      case DISRUPTION -> "disruption";
    };
  }
}
