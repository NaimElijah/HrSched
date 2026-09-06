package io.github.naimelijah.hrsched.workforce.adapter.out.persistence;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.domain.model.ContractTerms;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import io.github.naimelijah.hrsched.workforce.domain.model.ShiftPreference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
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
 * Driven adapter storing employees in PostgreSQL.
 *
 * <p>The richest aggregate here: an employee owns their unavailability and their preferences, and
 * references the qualifications they hold. Owned rows are deleted and re-inserted on every save
 * rather than diffed, because {@code Employee} compares by identity alone and so gives an adapter
 * no way to tell what changed.
 *
 * <p>Reading rebuilds through the aggregate's own withers rather than a back door, so every
 * invariant is re-checked on the way out of the database. If stored unavailability overlapped,
 * loading that employee fails loudly instead of quietly handing the domain a state it forbids.
 *
 * <p>A listing loads its children in one further query rather than one per employee, so the cost is
 * two round trips regardless of headcount.
 */
@Repository
public class JdbcEmployeeRepository implements EmployeeRepository {

  private final JdbcClient jdbc;

  public JdbcEmployeeRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(Employee employee) {
    UUID id = employee.id().value();
    jdbc.sql(
            """
            INSERT INTO employee (id, organisation_id, name, weekly_contracted_seconds)
            VALUES (:id, :organisation, :name, :contracted)
            ON CONFLICT (id) DO UPDATE SET organisation_id = EXCLUDED.organisation_id,
                                           name = EXCLUDED.name,
                                           weekly_contracted_seconds =
                                               EXCLUDED.weekly_contracted_seconds
            """)
        .param("id", id)
        .param("organisation", employee.organisation().value())
        .param("name", employee.name())
        .param(
            "contracted",
            employee
                .contractTerms()
                .map(ContractTerms::weeklyContractedHours)
                .map(Duration::toSeconds)
                .orElse(null))
        .update();

    replaceQualifications(employee, id);
    replaceUnavailability(employee, id);
    replacePreferences(employee, id);
  }

  private void replaceQualifications(Employee employee, UUID id) {
    jdbc.sql("DELETE FROM employee_qualification WHERE employee_id = :id").param("id", id).update();
    for (QualificationId qualification : employee.qualifications()) {
      jdbc.sql(
              """
              INSERT INTO employee_qualification (employee_id, qualification_id)
              VALUES (:id, :qualification)
              """)
          .param("id", id)
          .param("qualification", qualification.value())
          .update();
    }
  }

  private void replaceUnavailability(Employee employee, UUID id) {
    jdbc.sql("DELETE FROM employee_unavailability WHERE employee_id = :id")
        .param("id", id)
        .update();
    List<TimeRange> periods = employee.unavailability();
    for (int ordinal = 0; ordinal < periods.size(); ordinal++) {
      TimeRange period = periods.get(ordinal);
      jdbc.sql(
              """
              INSERT INTO employee_unavailability (employee_id, ordinal, starts_at, ends_at)
              VALUES (:id, :ordinal, :startsAt, :endsAt)
              """)
          .param("id", id)
          .param("ordinal", ordinal)
          .param("startsAt", period.start().atOffset(ZoneOffset.UTC))
          .param("endsAt", period.end().atOffset(ZoneOffset.UTC))
          .update();
    }
  }

  private void replacePreferences(Employee employee, UUID id) {
    jdbc.sql("DELETE FROM employee_preference WHERE employee_id = :id").param("id", id).update();
    List<ShiftPreference> preferences = employee.preferences();
    for (int ordinal = 0; ordinal < preferences.size(); ordinal++) {
      ShiftPreference preference = preferences.get(ordinal);
      jdbc.sql(
              """
              INSERT INTO employee_preference
                  (employee_id, ordinal, starts_at, ends_at, strength)
              VALUES (:id, :ordinal, :startsAt, :endsAt, :strength)
              """)
          .param("id", id)
          .param("ordinal", ordinal)
          .param("startsAt", preference.period().start().atOffset(ZoneOffset.UTC))
          .param("endsAt", preference.period().end().atOffset(ZoneOffset.UTC))
          .param("strength", preference.strength().name())
          .update();
    }
  }

  @Override
  public Optional<Employee> findById(OrganisationId organisation, EmployeeId id) {
    return jdbc.sql(
            """
            SELECT id, organisation_id, name, weekly_contracted_seconds FROM employee
            WHERE organisation_id = :organisation AND id = :id
            """)
        .param("organisation", organisation.value())
        .param("id", id.value())
        .query(JdbcEmployeeRepository::toBareEmployee)
        .optional()
        .map(bare -> withChildren(List.of(bare)).getFirst());
  }

  @Override
  public List<Employee> findByOrganisation(OrganisationId organisation) {
    return withChildren(
        jdbc.sql(
                """
                SELECT id, organisation_id, name, weekly_contracted_seconds FROM employee
                WHERE organisation_id = :organisation ORDER BY name
                """)
            .param("organisation", organisation.value())
            .query(JdbcEmployeeRepository::toBareEmployee)
            .list());
  }

  /** An employee with none of their owned rows yet, in the order the aggregate will be rebuilt. */
  private record BareEmployee(
      EmployeeId id, OrganisationId organisation, String name, Long contractedSeconds) {}

  /**
   * Reads the nullable column as a boxed {@link Long} rather than a {@code long} plus {@code
   * wasNull}, which reports on the last column read and would therefore be answering about the name
   * by the time it were asked.
   */
  private static BareEmployee toBareEmployee(ResultSet row, int rowNumber) throws SQLException {
    return new BareEmployee(
        new EmployeeId(row.getObject("id", UUID.class)),
        new OrganisationId(row.getObject("organisation_id", UUID.class)),
        row.getString("name"),
        row.getObject("weekly_contracted_seconds", Long.class));
  }

  private List<Employee> withChildren(List<BareEmployee> bare) {
    if (bare.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = bare.stream().map(employee -> employee.id().value()).toList();
    Map<UUID, List<QualificationId>> qualifications = qualificationsOf(ids);
    Map<UUID, List<TimeRange>> unavailability = unavailabilityOf(ids);
    Map<UUID, List<ShiftPreference>> preferences = preferencesOf(ids);

    List<Employee> employees = new ArrayList<>(bare.size());
    for (BareEmployee one : bare) {
      UUID id = one.id().value();
      Employee employee = new Employee(one.id(), one.organisation(), one.name());
      for (QualificationId qualification : qualifications.getOrDefault(id, List.of())) {
        employee = employee.holding(qualification);
      }
      for (TimeRange period : unavailability.getOrDefault(id, List.of())) {
        employee = employee.unavailableFor(period);
      }
      for (ShiftPreference preference : preferences.getOrDefault(id, List.of())) {
        employee = employee.preferring(preference);
      }
      if (one.contractedSeconds() != null) {
        employee =
            employee.contractedTo(new ContractTerms(Duration.ofSeconds(one.contractedSeconds())));
      }
      employees.add(employee);
    }
    return employees;
  }

  /** One owned or referenced row, still attached to the employee it belongs to. */
  private record Owned<T>(UUID employee, T value) {}

  private static <T> Map<UUID, List<T>> groupByEmployee(List<Owned<T>> rows) {
    Map<UUID, List<T>> byEmployee = new LinkedHashMap<>();
    for (Owned<T> row : rows) {
      byEmployee.computeIfAbsent(row.employee(), key -> new ArrayList<>()).add(row.value());
    }
    return byEmployee;
  }

  private Map<UUID, List<QualificationId>> qualificationsOf(List<UUID> ids) {
    return groupByEmployee(
        jdbc.sql(
                """
                SELECT employee_id, qualification_id FROM employee_qualification
                WHERE employee_id IN (:ids) ORDER BY employee_id, qualification_id
                """)
            .param("ids", ids)
            .query(
                (row, rowNumber) ->
                    new Owned<>(
                        row.getObject("employee_id", UUID.class),
                        new QualificationId(row.getObject("qualification_id", UUID.class))))
            .list());
  }

  private Map<UUID, List<TimeRange>> unavailabilityOf(List<UUID> ids) {
    return groupByEmployee(
        jdbc.sql(
                """
                SELECT employee_id, starts_at, ends_at FROM employee_unavailability
                WHERE employee_id IN (:ids) ORDER BY employee_id, ordinal
                """)
            .param("ids", ids)
            .query(
                (row, rowNumber) ->
                    new Owned<>(row.getObject("employee_id", UUID.class), period(row)))
            .list());
  }

  private Map<UUID, List<ShiftPreference>> preferencesOf(List<UUID> ids) {
    return groupByEmployee(
        jdbc.sql(
                """
                SELECT employee_id, starts_at, ends_at, strength FROM employee_preference
                WHERE employee_id IN (:ids) ORDER BY employee_id, ordinal
                """)
            .param("ids", ids)
            .query(
                (row, rowNumber) ->
                    new Owned<>(
                        row.getObject("employee_id", UUID.class),
                        new ShiftPreference(
                            period(row), PreferenceStrength.valueOf(row.getString("strength")))))
            .list());
  }

  /**
   * Read as an {@link OffsetDateTime} rather than a {@code java.sql.Timestamp}: {@code timestamptz}
   * carries a real offset and a {@code Timestamp} carries none, so the latter round-trips through
   * whatever default time zone the JVM happens to be in.
   */
  private static TimeRange period(ResultSet row) throws SQLException {
    return new TimeRange(
        row.getObject("starts_at", OffsetDateTime.class).toInstant(),
        row.getObject("ends_at", OffsetDateTime.class).toInstant());
  }
}
