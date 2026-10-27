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
   * The organisation's shifts overlapping {@code :spanStart} to {@code :spanEnd}: one predicate,
   * shared by the read of the shifts and the read of their qualifications so the two cannot choose
   * different shifts.
   */
  private static final String WITHIN_THE_SPAN =
      """
      organisation_id = :organisation
        AND starts_at < :spanEnd
        AND :spanStart < ends_at
      """;

  /** The shifts a span holds, earliest first. */
  private static final String SHIFTS_WITHIN_THE_SPAN =
      """
      SELECT id, organisation_id, starts_at, ends_at, zone, required_headcount, unpopular
      FROM shift WHERE
      """
          + WITHIN_THE_SPAN
          + """
          ORDER BY starts_at
          """;

  /**
   * The qualifications of the shifts a span holds, chosen by the predicate that chose the shifts
   * rather than by binding their identities: PostgreSQL's wire protocol counts a statement's
   * parameters in 16 bits, and shifts are never deleted, so an organisation passes 65,535 of them
   * by being used.
   */
  private static final String QUALIFICATIONS_WITHIN_THE_SPAN =
      """
      SELECT shift_id, qualification_id FROM shift_required_qualification
      WHERE shift_id IN (SELECT id FROM shift WHERE
      """
          + WITHIN_THE_SPAN
          + """
          )
          ORDER BY shift_id, qualification_id
          """;

  /** The qualifications of one shift, by identity and only within its organisation. */
  private static final String QUALIFICATIONS_OF_ONE_SHIFT =
      """
      SELECT shift_id, qualification_id FROM shift_required_qualification
      WHERE shift_id IN (SELECT id FROM shift WHERE organisation_id = :organisation AND id = :id)
      ORDER BY shift_id, qualification_id
      """;

  private final JdbcClient jdbc;

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
    return jdbc.sql(
            """
            SELECT id, organisation_id, starts_at, ends_at, zone, required_headcount, unpopular FROM shift
            WHERE organisation_id = :organisation AND id = :id
            """)
        .param("organisation", organisation.value())
        .param("id", id.value())
        .query(JdbcShiftRepository::toBareShift)
        .optional()
        .map(
            bare ->
                withQualifications(
                        List.of(bare),
                        jdbc.sql(QUALIFICATIONS_OF_ONE_SHIFT)
                            .param("organisation", organisation.value())
                            .param("id", id.value()))
                    .getFirst());
  }

  @Override
  public List<Shift> findWithin(OrganisationId organisation, TimeRange within) {
    return withQualifications(
        withinTheSpan(SHIFTS_WITHIN_THE_SPAN, organisation, within)
            .query(JdbcShiftRepository::toBareShift)
            .list(),
        withinTheSpan(QUALIFICATIONS_WITHIN_THE_SPAN, organisation, within));
  }

  private JdbcClient.StatementSpec withinTheSpan(
      String sql, OrganisationId organisation, TimeRange within) {
    return jdbc.sql(sql)
        .param("organisation", organisation.value())
        .param("spanStart", within.start().atOffset(ZoneOffset.UTC))
        .param("spanEnd", within.end().atOffset(ZoneOffset.UTC));
  }

  /** A shift with none of its required qualifications yet. */
  private record BareShift(
      ShiftId id,
      OrganisationId organisation,
      TimeRange period,
      ZoneId zone,
      int requiredHeadcount,
      boolean unpopular) {}

  private static BareShift toBareShift(ResultSet row, int rowNumber) throws SQLException {
    return new BareShift(
        new ShiftId(row.getObject("id", UUID.class)),
        new OrganisationId(row.getObject("organisation_id", UUID.class)),
        new TimeRange(
            row.getObject("starts_at", OffsetDateTime.class).toInstant(),
            row.getObject("ends_at", OffsetDateTime.class).toInstant()),
        ZoneId.of(row.getString("zone")),
        row.getInt("required_headcount"),
        row.getBoolean("unpopular"));
  }

  /**
   * @param qualifications a statement reading the required qualifications of at least every shift
   *     in {@code bare}; one of any other shift is ignored
   */
  private List<Shift> withQualifications(
      List<BareShift> bare, JdbcClient.StatementSpec qualifications) {
    if (bare.isEmpty()) {
      return List.of();
    }
    Map<UUID, Set<QualificationId>> required = requiredQualificationsOf(qualifications);
    return bare.stream()
        .map(
            shift ->
                new Shift(
                    shift.id(),
                    shift.organisation(),
                    shift.period(),
                    shift.zone(),
                    shift.requiredHeadcount(),
                    required.getOrDefault(shift.id().value(), Set.of()),
                    shift.unpopular()))
        .toList();
  }

  private static Map<UUID, Set<QualificationId>> requiredQualificationsOf(
      JdbcClient.StatementSpec qualifications) {
    record Required(UUID shift, QualificationId qualification) {}
    List<Required> rows =
        qualifications
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
