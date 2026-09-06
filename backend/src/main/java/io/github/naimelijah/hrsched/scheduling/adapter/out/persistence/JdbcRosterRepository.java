package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing rosters in PostgreSQL.
 *
 * <p>A roster owns its assignments, so they are deleted and re-inserted on every save; the
 * composite primary key on the child table is {@code Roster.requireNoDuplicates} restated in the
 * schema.
 *
 * <p>Reading rebuilds the aggregate through its own transitions rather than a back door, which is
 * why a superseded roster is assembled as draft, then published, then superseded: those are the
 * only legal routes to that state, and going round them would let this adapter construct a roster
 * the domain says cannot exist.
 *
 * <p>Deliberately promises no ordering, matching the port. Its siblings sort on a field; there is
 * no field here that a caller has been told to expect an order by.
 */
@Repository
public class JdbcRosterRepository implements RosterRepository {

  private final JdbcClient jdbc;

  public JdbcRosterRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(Roster roster) {
    UUID id = roster.id().value();
    jdbc.sql(
            """
            INSERT INTO roster (id, organisation_id, starts_at, ends_at, status,
                                validated_against_constraint_version)
            VALUES (:id, :organisation, :startsAt, :endsAt, :status, :version)
            ON CONFLICT (id) DO UPDATE SET organisation_id = EXCLUDED.organisation_id,
                                           starts_at = EXCLUDED.starts_at,
                                           ends_at = EXCLUDED.ends_at,
                                           status = EXCLUDED.status,
                                           validated_against_constraint_version =
                                               EXCLUDED.validated_against_constraint_version
            """)
        .param("id", id)
        .param("organisation", roster.organisation().value())
        .param("startsAt", roster.period().start().atOffset(ZoneOffset.UTC))
        .param("endsAt", roster.period().end().atOffset(ZoneOffset.UTC))
        .param("status", roster.status().name())
        .param("version", roster.validatedAgainstConstraintVersion())
        .update();

    jdbc.sql("DELETE FROM roster_assignment WHERE roster_id = :id").param("id", id).update();
    for (Assignment assignment : roster.assignments()) {
      jdbc.sql(
              """
              INSERT INTO roster_assignment (roster_id, shift_id, employee_id)
              VALUES (:id, :shift, :employee)
              """)
          .param("id", id)
          .param("shift", assignment.shift().value())
          .param("employee", assignment.employee().value())
          .update();
    }
  }

  @Override
  public Optional<Roster> findById(OrganisationId organisation, RosterId id) {
    return jdbc.sql(
            """
            SELECT id, organisation_id, starts_at, ends_at, status,
                   validated_against_constraint_version
            FROM roster WHERE organisation_id = :organisation AND id = :id
            """)
        .param("organisation", organisation.value())
        .param("id", id.value())
        .query(JdbcRosterRepository::toBareRoster)
        .optional()
        .map(bare -> withAssignments(List.of(bare)).getFirst());
  }

  @Override
  public List<Roster> findByOrganisation(OrganisationId organisation) {
    return withAssignments(
        jdbc.sql(
                """
                SELECT id, organisation_id, starts_at, ends_at, status,
                       validated_against_constraint_version
                FROM roster WHERE organisation_id = :organisation
                """)
            .param("organisation", organisation.value())
            .query(JdbcRosterRepository::toBareRoster)
            .list());
  }

  /** A roster's own row, before its assignments are attached. */
  private record BareRoster(
      RosterId id,
      OrganisationId organisation,
      TimeRange period,
      RosterStatus status,
      Integer constraintVersion) {}

  private static BareRoster toBareRoster(ResultSet row, int rowNumber) throws SQLException {
    return new BareRoster(
        new RosterId(row.getObject("id", UUID.class)),
        new OrganisationId(row.getObject("organisation_id", UUID.class)),
        new TimeRange(
            row.getObject("starts_at", OffsetDateTime.class).toInstant(),
            row.getObject("ends_at", OffsetDateTime.class).toInstant()),
        RosterStatus.valueOf(row.getString("status")),
        row.getObject("validated_against_constraint_version", Integer.class));
  }

  private List<Roster> withAssignments(List<BareRoster> bare) {
    if (bare.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<Assignment>> assignments =
        assignmentsOf(bare.stream().map(roster -> roster.id().value()).toList());
    return bare.stream()
        .map(roster -> rebuild(roster, assignments.getOrDefault(roster.id().value(), List.of())))
        .toList();
  }

  /**
   * Walks the aggregate back to the state it was stored in, using only the transitions the domain
   * offers. A published or superseded roster is therefore built as a draft first, because that is
   * the only way one is ever legitimately reached.
   */
  private static Roster rebuild(BareRoster bare, List<Assignment> assignments) {
    Roster draft =
        new Roster(bare.id(), bare.organisation(), bare.period()).withAssignments(assignments);
    return switch (bare.status()) {
      case DRAFT -> draft;
      case PUBLISHED -> draft.publishedUnder(bare.constraintVersion());
      case SUPERSEDED -> draft.publishedUnder(bare.constraintVersion()).superseded();
    };
  }

  private Map<UUID, List<Assignment>> assignmentsOf(List<UUID> ids) {
    record Owned(UUID roster, Assignment assignment) {}
    List<Owned> rows =
        jdbc.sql(
                """
                SELECT roster_id, shift_id, employee_id FROM roster_assignment
                WHERE roster_id IN (:ids) ORDER BY roster_id, shift_id, employee_id
                """)
            .param("ids", ids)
            .query(
                (row, rowNumber) ->
                    new Owned(
                        row.getObject("roster_id", UUID.class),
                        new Assignment(
                            new ShiftId(row.getObject("shift_id", UUID.class)),
                            new EmployeeId(row.getObject("employee_id", UUID.class)))))
            .list();

    Map<UUID, List<Assignment>> byRoster = new LinkedHashMap<>();
    for (Owned row : rows) {
      byRoster.computeIfAbsent(row.roster(), key -> new ArrayList<>()).add(row.assignment());
    }
    return byRoster;
  }
}
