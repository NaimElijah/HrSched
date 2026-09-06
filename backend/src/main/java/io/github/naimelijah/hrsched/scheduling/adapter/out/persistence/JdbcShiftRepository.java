package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing shifts in PostgreSQL.
 *
 * <p>{@code findWithin} asks for overlap, not containment, and half-open at both ends: a shift
 * ending exactly when the span begins is not in it. That is {@code TimeRange.overlaps} expressed as
 * a predicate, and the two must not drift apart.
 *
 * <p>The required qualifications are a reference set rather than owned rows, and they name another
 * context's table, so there is no foreign key — scheduling holds a {@link QualificationId} and
 * never a workforce entity (ADR-008).
 */
@Repository
public class JdbcShiftRepository implements ShiftRepository {

  private final JdbcClient jdbc;

  public JdbcShiftRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(Shift shift) {
    UUID id = shift.id().value();
    jdbc.sql(
            """
            INSERT INTO shift (id, organisation_id, starts_at, ends_at, required_headcount)
            VALUES (:id, :organisation, :startsAt, :endsAt, :headcount)
            ON CONFLICT (id) DO UPDATE SET organisation_id = EXCLUDED.organisation_id,
                                           starts_at = EXCLUDED.starts_at,
                                           ends_at = EXCLUDED.ends_at,
                                           required_headcount = EXCLUDED.required_headcount
            """)
        .param("id", id)
        .param("organisation", shift.organisation().value())
        .param("startsAt", shift.period().start().atOffset(ZoneOffset.UTC))
        .param("endsAt", shift.period().end().atOffset(ZoneOffset.UTC))
        .param("headcount", shift.requiredHeadcount())
        .update();

    jdbc.sql("DELETE FROM shift_required_qualification WHERE shift_id = :id")
        .param("id", id)
        .update();
    for (QualificationId qualification : shift.requiredQualifications()) {
      jdbc.sql(
              """
              INSERT INTO shift_required_qualification (shift_id, qualification_id)
              VALUES (:id, :qualification)
              """)
          .param("id", id)
          .param("qualification", qualification.value())
          .update();
    }
  }

  @Override
  public Optional<Shift> findById(OrganisationId organisation, ShiftId id) {
    return jdbc.sql(
            """
            SELECT id, organisation_id, starts_at, ends_at, required_headcount FROM shift
            WHERE organisation_id = :organisation AND id = :id
            """)
        .param("organisation", organisation.value())
        .param("id", id.value())
        .query(JdbcShiftRepository::toBareShift)
        .optional()
        .map(bare -> withQualifications(List.of(bare)).getFirst());
  }

  @Override
  public List<Shift> findWithin(OrganisationId organisation, TimeRange within) {
    return withQualifications(
        jdbc.sql(
                """
                SELECT id, organisation_id, starts_at, ends_at, required_headcount FROM shift
                WHERE organisation_id = :organisation
                  AND starts_at < :spanEnd
                  AND :spanStart < ends_at
                ORDER BY starts_at
                """)
            .param("organisation", organisation.value())
            .param("spanStart", within.start().atOffset(ZoneOffset.UTC))
            .param("spanEnd", within.end().atOffset(ZoneOffset.UTC))
            .query(JdbcShiftRepository::toBareShift)
            .list());
  }

  /** A shift with none of its required qualifications yet. */
  private record BareShift(
      ShiftId id, OrganisationId organisation, TimeRange period, int requiredHeadcount) {}

  private static BareShift toBareShift(ResultSet row, int rowNumber) throws SQLException {
    return new BareShift(
        new ShiftId(row.getObject("id", UUID.class)),
        new OrganisationId(row.getObject("organisation_id", UUID.class)),
        new TimeRange(
            row.getObject("starts_at", OffsetDateTime.class).toInstant(),
            row.getObject("ends_at", OffsetDateTime.class).toInstant()),
        row.getInt("required_headcount"));
  }

  private List<Shift> withQualifications(List<BareShift> bare) {
    if (bare.isEmpty()) {
      return List.of();
    }
    Map<UUID, Set<QualificationId>> required =
        requiredQualificationsOf(bare.stream().map(shift -> shift.id().value()).toList());
    return bare.stream()
        .map(
            shift ->
                new Shift(
                    shift.id(),
                    shift.organisation(),
                    shift.period(),
                    shift.requiredHeadcount(),
                    required.getOrDefault(shift.id().value(), Set.of())))
        .toList();
  }

  private Map<UUID, Set<QualificationId>> requiredQualificationsOf(List<UUID> ids) {
    record Required(UUID shift, QualificationId qualification) {}
    List<Required> rows =
        jdbc.sql(
                """
                SELECT shift_id, qualification_id FROM shift_required_qualification
                WHERE shift_id IN (:ids) ORDER BY shift_id, qualification_id
                """)
            .param("ids", ids)
            .query(
                (row, rowNumber) ->
                    new Required(
                        row.getObject("shift_id", UUID.class),
                        new QualificationId(row.getObject("qualification_id", UUID.class))))
            .list();

    Map<UUID, Set<QualificationId>> byShift = new LinkedHashMap<>();
    for (Required row : rows) {
      byShift.computeIfAbsent(row.shift(), key -> new LinkedHashSet<>()).add(row.qualification());
    }
    return byShift;
  }
}
