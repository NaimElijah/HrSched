package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerationRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationId;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterGeneration;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter keeping each generation in PostgreSQL (ADR-046).
 *
 * <p>A generation ends once. The guard is in the statement rather than read first: a save over a
 * row that has already ended, or that another organisation holds, touches nothing, and is refused.
 */
@Repository
public class JdbcRosterGenerationRepository implements RosterGenerationRepository {

  private final JdbcClient jdbc;

  public JdbcRosterGenerationRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(RosterGeneration generation) {
    UUID id = generation.id().value();
    int stored =
        jdbc.sql(
                """
                INSERT INTO roster_generation (id, organisation_id, starts_at, ends_at,
                                               requested_at, lost_at, status, roster_id,
                                               finished_at)
                VALUES (:id, :organisation, :startsAt, :endsAt, :requestedAt, :lostAt, :status,
                        :roster, :finishedAt)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status,
                                               roster_id = EXCLUDED.roster_id,
                                               finished_at = EXCLUDED.finished_at
                WHERE roster_generation.organisation_id = EXCLUDED.organisation_id
                  AND roster_generation.status = 'PENDING'
                """)
            .param("id", id)
            .param("organisation", generation.organisation().value())
            .param("startsAt", atUtc(generation.period().start()))
            .param("endsAt", atUtc(generation.period().end()))
            .param("requestedAt", atUtc(generation.requestedAt()))
            .param("lostAt", atUtc(generation.lostAt()))
            .param("status", generation.status().name())
            .param("roster", generation.roster().map(RosterId::value).orElse(null))
            .param(
                "finishedAt",
                generation.finishedAt().map(JdbcRosterGenerationRepository::atUtc).orElse(null))
            .update();
    if (stored != 1) {
      throw new IllegalStateException(
          "Generation %s has already ended, or belongs to another organisation".formatted(id));
    }
    List<ConstraintViolation> violations = generation.violations();
    for (int position = 0; position < violations.size(); position++) {
      ConstraintViolation violation = violations.get(position);
      jdbc.sql(
              """
              INSERT INTO roster_generation_violation (generation_id, position, employee_id,
                                                       constraint_name, detail)
              VALUES (:id, :position, :employee, :constraint, :detail)
              """)
          .param("id", id)
          .param("position", position)
          .param("employee", violation.employee().value())
          .param("constraint", violation.constraint())
          .param("detail", violation.detail())
          .update();
    }
  }

  @Override
  public Optional<RosterGeneration> findById(OrganisationId organisation, GenerationId id) {
    List<ConstraintViolation> violations =
        jdbc.sql(
                """
                SELECT employee_id, constraint_name, detail FROM roster_generation_violation
                WHERE generation_id = :id ORDER BY position
                """)
            .param("id", id.value())
            .query(
                (row, rowNumber) ->
                    new ConstraintViolation(
                        new EmployeeId(row.getObject("employee_id", UUID.class)),
                        row.getString("constraint_name"),
                        row.getString("detail")))
            .list();
    return jdbc.sql(
            """
            SELECT id, starts_at, ends_at, requested_at, lost_at, status, roster_id, finished_at
            FROM roster_generation
            WHERE organisation_id = :organisation AND id = :id
            """)
        .param("organisation", organisation.value())
        .param("id", id.value())
        .query((row, rowNumber) -> toGeneration(organisation, row, violations))
        .optional();
  }

  private static RosterGeneration toGeneration(
      OrganisationId organisation, ResultSet row, List<ConstraintViolation> violations)
      throws SQLException {
    return RosterGeneration.restored(
        new GenerationId(row.getObject("id", UUID.class)),
        organisation,
        new TimeRange(instant(row, "starts_at"), instant(row, "ends_at")),
        instant(row, "requested_at"),
        instant(row, "lost_at"),
        GenerationStatus.valueOf(row.getString("status")),
        Optional.ofNullable(row.getObject("roster_id", UUID.class)).map(RosterId::new),
        violations,
        Optional.ofNullable(row.getObject("finished_at", OffsetDateTime.class))
            .map(OffsetDateTime::toInstant));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static OffsetDateTime atUtc(Instant moment) {
    return moment.atOffset(ZoneOffset.UTC);
  }
}
