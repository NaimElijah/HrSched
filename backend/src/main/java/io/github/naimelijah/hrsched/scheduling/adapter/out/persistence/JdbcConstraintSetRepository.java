package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintsRevisedConcurrentlyException;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumConsecutiveWorkingDays;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumWeeklyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MinimumRestBetweenAssignments;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingTimeConstraint;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing an organisation's working-time rules in PostgreSQL.
 *
 * <p>Keyed by {@code (organisation_id, version)} rather than by organisation alone, so every
 * revision is kept. A published roster records the constraint version that approved it, and that
 * record is worthless if the version cannot be read back: {@code findCurrent} reads the highest
 * version, and {@code findVersion} any one of them (Q9).
 *
 * <p><strong>No threshold appears in this class, and none may.</strong> A rest period or an hours
 * cap is a value an organisation configured, and every one of them arrives here as data.
 */
@Repository
public class JdbcConstraintSetRepository implements ConstraintSetRepository {

  /**
   * The stored name of each constraint in the sealed hierarchy. Kept beside the mapping rather than
   * derived from the class name, so renaming a class cannot silently orphan every row written under
   * its old name.
   */
  private static final String MINIMUM_REST = "MINIMUM_REST_BETWEEN_ASSIGNMENTS";

  private static final String MAXIMUM_DAILY = "MAXIMUM_DAILY_WORKING_TIME";
  private static final String MAXIMUM_WEEKLY = "MAXIMUM_WEEKLY_WORKING_TIME";
  private static final String MAXIMUM_CONSECUTIVE_DAYS = "MAXIMUM_CONSECUTIVE_WORKING_DAYS";

  /** PostgreSQL's SQLSTATE for {@code unique_violation}. */
  private static final String UNIQUE_VIOLATION = "23505";

  private final JdbcClient jdbc;

  public JdbcConstraintSetRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /**
   * Stores a revision.
   *
   * <p>A plain {@code INSERT}: revising always mints the next version, so a conflict here means two
   * Admins computed the same next version from the same current one, and exactly one of them is
   * about to lose their revision. Silencing that with {@code ON CONFLICT DO NOTHING} would let the
   * second overwrite the first's constraints while its audit line claimed success — so the unique
   * violation refuses the second and takes its transaction with it (ADR-023), as a conflict the
   * caller can act on by reloading the rules (#238).
   *
   * @throws ConstraintsRevisedConcurrentlyException if that version is already stored
   */
  @Override
  public void save(ConstraintSet constraintSet) {
    try {
      jdbc.sql(
              """
              INSERT INTO constraint_set (organisation_id, version)
              VALUES (:organisation, :version)
              """)
          .param("organisation", constraintSet.organisation().value())
          .param("version", constraintSet.version())
          .update();
    } catch (DataAccessException refused) {
      // The primary key is the only unique index this insert can break.
      if (isUniqueViolation(refused)) {
        throw new ConstraintsRevisedConcurrentlyException(constraintSet.version(), refused);
      }
      throw refused;
    }

    jdbc.sql(
            """
            DELETE FROM working_time_constraint
            WHERE organisation_id = :organisation AND version = :version
            """)
        .param("organisation", constraintSet.organisation().value())
        .param("version", constraintSet.version())
        .update();

    List<WorkingTimeConstraint> constraints = constraintSet.constraints();
    for (int ordinal = 0; ordinal < constraints.size(); ordinal++) {
      insert(constraintSet, ordinal, constraints.get(ordinal));
    }
  }

  /**
   * Exhaustive over the sealed hierarchy, with no {@code default}: a new constraint type will not
   * compile until it is given a stored representation here, which is what stops one silently
   * becoming unstorable.
   */
  private void insert(ConstraintSet set, int ordinal, WorkingTimeConstraint constraint) {
    String kind;
    Long durationSeconds = null;
    Integer dayCount = null;
    switch (constraint) {
      case MinimumRestBetweenAssignments rest -> {
        kind = MINIMUM_REST;
        durationSeconds = rest.minimumRest().toSeconds();
      }
      case MaximumDailyWorkingTime daily -> {
        kind = MAXIMUM_DAILY;
        durationSeconds = daily.maximum().toSeconds();
      }
      case MaximumWeeklyWorkingTime weekly -> {
        kind = MAXIMUM_WEEKLY;
        durationSeconds = weekly.maximum().toSeconds();
      }
      case MaximumConsecutiveWorkingDays days -> {
        kind = MAXIMUM_CONSECUTIVE_DAYS;
        dayCount = days.maximum();
      }
    }

    jdbc.sql(
            """
            INSERT INTO working_time_constraint
                (organisation_id, version, ordinal, kind, duration_seconds, day_count)
            VALUES (:organisation, :version, :ordinal, :kind, :durationSeconds, :dayCount)
            """)
        .param("organisation", set.organisation().value())
        .param("version", set.version())
        .param("ordinal", ordinal)
        .param("kind", kind)
        .param("durationSeconds", durationSeconds)
        .param("dayCount", dayCount)
        .update();
  }

  private static boolean isUniqueViolation(DataAccessException refused) {
    return NestedExceptionUtils.getMostSpecificCause(refused) instanceof SQLException cause
        && UNIQUE_VIOLATION.equals(cause.getSQLState());
  }

  @Override
  public Optional<ConstraintSet> findCurrent(OrganisationId organisation) {
    return jdbc.sql(
            """
            SELECT max(version) FROM constraint_set WHERE organisation_id = :organisation
            """)
        .param("organisation", organisation.value())
        .query(Integer.class)
        .optional()
        .map(
            version ->
                new ConstraintSet(organisation, version, constraintsOf(organisation, version)));
  }

  @Override
  public Optional<ConstraintSet> findVersion(OrganisationId organisation, int version) {
    // The version's own row, not its constraints: a version that switched every rule off has no
    // constraint rows at all, and it is still a version.
    return jdbc.sql(
            """
            SELECT version FROM constraint_set
            WHERE organisation_id = :organisation AND version = :version
            """)
        .param("organisation", organisation.value())
        .param("version", version)
        .query(Integer.class)
        .optional()
        .map(
            stored -> new ConstraintSet(organisation, stored, constraintsOf(organisation, stored)));
  }

  private List<WorkingTimeConstraint> constraintsOf(OrganisationId organisation, int version) {
    return jdbc.sql(
            """
            SELECT kind, duration_seconds, day_count FROM working_time_constraint
            WHERE organisation_id = :organisation AND version = :version
            ORDER BY ordinal
            """)
        .param("organisation", organisation.value())
        .param("version", version)
        .query(JdbcConstraintSetRepository::toConstraint)
        .list();
  }

  private static WorkingTimeConstraint toConstraint(ResultSet row, int rowNumber)
      throws SQLException {
    String kind = row.getString("kind");
    return switch (kind) {
      case MINIMUM_REST ->
          new MinimumRestBetweenAssignments(Duration.ofSeconds(row.getLong("duration_seconds")));
      case MAXIMUM_DAILY ->
          new MaximumDailyWorkingTime(Duration.ofSeconds(row.getLong("duration_seconds")));
      case MAXIMUM_WEEKLY ->
          new MaximumWeeklyWorkingTime(Duration.ofSeconds(row.getLong("duration_seconds")));
      case MAXIMUM_CONSECUTIVE_DAYS -> new MaximumConsecutiveWorkingDays(row.getInt("day_count"));
      default ->
          throw new IllegalStateException(
              "Stored working-time constraint of unknown kind: %s".formatted(kind));
    };
  }
}
