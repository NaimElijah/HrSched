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
import java.time.ZoneId;
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

  /**
   * Each shift with its required qualifications, one row per qualification and one with a null
   * qualification for a shift requiring none. One statement, so the shifts and their qualifications
   * come from one snapshot: under READ COMMITTED each statement takes its own, and a save
   * committing between two reads could pair a shift with another version's requirements. The join
   * reaches the child table only through the shifts the {@code WHERE} chose, so it is scoped to the
   * organisation, and binds no parameter per shift: PostgreSQL's wire protocol counts a statement's
   * parameters in 16 bits, and shifts are never deleted, so an organisation passes 65,535 of them
   * by being used.
   */
  private static final String SHIFTS_WITH_QUALIFICATIONS =
      """
      SELECT shift.id, shift.organisation_id, shift.starts_at, shift.ends_at, shift.zone,
             shift.required_headcount, shift.unpopular, required.qualification_id
      FROM shift
      LEFT JOIN shift_required_qualification required ON required.shift_id = shift.id
      WHERE shift.organisation_id = :organisation AND
      """;

  /**
   * The organisation's shifts overlapping {@code :spanStart} to {@code :spanEnd}, earliest first.
   */
  private static final String SHIFTS_WITHIN_THE_SPAN =
      SHIFTS_WITH_QUALIFICATIONS
          + """
            shift.starts_at < :spanEnd AND :spanStart < shift.ends_at
          ORDER BY shift.starts_at, shift.id, required.qualification_id
          """;

  /** One shift, by identity and only within its organisation. */
  private static final String ONE_SHIFT =
      SHIFTS_WITH_QUALIFICATIONS
          + """
            shift.id = :id
          ORDER BY required.qualification_id
          """;

  private final JdbcClient jdbc;

  /**
   * @param jdbc the client every statement of this repository runs through; must not be null
   * @throws NullPointerException if {@code jdbc} is null
   */
  public JdbcShiftRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(Shift shift) {
    UUID id = shift.id().value();
    int stored =
        jdbc.sql(
                """
            INSERT INTO shift (id, organisation_id, starts_at, ends_at, zone, required_headcount,
                               unpopular)
            VALUES (:id, :organisation, :startsAt, :endsAt, :zone, :headcount, :unpopular)
            ON CONFLICT (id) DO UPDATE SET starts_at = EXCLUDED.starts_at,
                                           ends_at = EXCLUDED.ends_at,
                                           zone = EXCLUDED.zone,
                                           required_headcount = EXCLUDED.required_headcount,
                                           unpopular = EXCLUDED.unpopular
            WHERE shift.organisation_id = EXCLUDED.organisation_id
            """)
            .param("id", id)
            .param("organisation", shift.organisation().value())
            .param("startsAt", shift.period().start().atOffset(ZoneOffset.UTC))
            .param("endsAt", shift.period().end().atOffset(ZoneOffset.UTC))
            .param("zone", shift.zone().getId())
            .param("headcount", shift.requiredHeadcount())
            .param("unpopular", shift.unpopular())
            .update();
    requireStored(stored, id);

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
    return shiftsOf(
            jdbc.sql(ONE_SHIFT).param("organisation", organisation.value()).param("id", id.value()))
        .stream()
        .findFirst();
  }

  @Override
  public List<Shift> findWithin(OrganisationId organisation, TimeRange within) {
    return shiftsOf(
        jdbc.sql(SHIFTS_WITHIN_THE_SPAN)
            .param("organisation", organisation.value())
            .param("spanStart", within.start().atOffset(ZoneOffset.UTC))
            .param("spanEnd", within.end().atOffset(ZoneOffset.UTC)));
  }

  /** One row of {@link #SHIFTS_WITH_QUALIFICATIONS}: a shift, and one qualification it requires. */
  private record ShiftRow(
      ShiftId id,
      OrganisationId organisation,
      TimeRange period,
      ZoneId zone,
      int requiredHeadcount,
      boolean unpopular,
      Optional<QualificationId> qualification) {}

  /** Folds the rows of each shift into it, keeping the order the statement read them in. */
  private static List<Shift> shiftsOf(JdbcClient.StatementSpec statement) {
    Map<ShiftId, ShiftRow> shifts = new LinkedHashMap<>();
    Map<ShiftId, Set<QualificationId>> required = new LinkedHashMap<>();
    for (ShiftRow row : statement.query(JdbcShiftRepository::toShiftRow).list()) {
      shifts.putIfAbsent(row.id(), row);
      Set<QualificationId> ofShift =
          required.computeIfAbsent(row.id(), key -> new LinkedHashSet<>());
      row.qualification().ifPresent(ofShift::add);
    }
    return shifts.values().stream()
        .map(
            shift ->
                new Shift(
                    shift.id(),
                    shift.organisation(),
                    shift.period(),
                    shift.zone(),
                    shift.requiredHeadcount(),
                    required.get(shift.id()),
                    shift.unpopular()))
        .toList();
  }

  private static ShiftRow toShiftRow(ResultSet row, int rowNumber) throws SQLException {
    return new ShiftRow(
        new ShiftId(row.getObject("id", UUID.class)),
        new OrganisationId(row.getObject("organisation_id", UUID.class)),
        new TimeRange(
            row.getObject("starts_at", OffsetDateTime.class).toInstant(),
            row.getObject("ends_at", OffsetDateTime.class).toInstant()),
        ZoneId.of(row.getString("zone")),
        row.getInt("required_headcount"),
        row.getBoolean("unpopular"),
        Optional.ofNullable(row.getObject("qualification_id", UUID.class))
            .map(QualificationId::new));
  }

  /**
   * A save carries no {@code OrganisationId}, so the upsert's {@code WHERE} is the only thing
   * standing between a re-used identity and another tenant's row. It makes the mismatched case
   * affect nothing rather than overwrite; this turns that silence into a refusal, because a write
   * that quietly did nothing is the worse of the two failures.
   */
  private static void requireStored(int rows, UUID id) {
    if (rows == 0) {
      throw new IllegalStateException(
          "Refused to store %s: that identity already belongs to another organisation"
              .formatted(id));
    }
  }
}
