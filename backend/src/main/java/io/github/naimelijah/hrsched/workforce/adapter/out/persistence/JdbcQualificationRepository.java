package io.github.naimelijah.hrsched.workforce.adapter.out.persistence;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing qualifications in PostgreSQL.
 *
 * <p>Every read names the organisation in its {@code WHERE} clause, so a cross-tenant read would
 * have to be written on purpose (ADR-009). There is no unique index on the name: the domain lets
 * two qualifications share one, and a constraint the domain does not enforce would be a new rule
 * introduced as a storage detail.
 */
@Repository
public class JdbcQualificationRepository implements QualificationRepository {

  private final JdbcClient jdbc;

  public JdbcQualificationRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(Qualification qualification) {
    jdbc.sql(
            """
            INSERT INTO qualification (id, organisation_id, name)
            VALUES (:id, :organisation, :name)
            ON CONFLICT (id) DO UPDATE SET organisation_id = EXCLUDED.organisation_id,
                                           name = EXCLUDED.name
            """)
        .param("id", qualification.id().value())
        .param("organisation", qualification.organisation().value())
        .param("name", qualification.name())
        .update();
  }

  @Override
  public Optional<Qualification> findById(OrganisationId organisation, QualificationId id) {
    return jdbc.sql(
            """
            SELECT id, organisation_id, name FROM qualification
            WHERE organisation_id = :organisation AND id = :id
            """)
        .param("organisation", organisation.value())
        .param("id", id.value())
        .query(JdbcQualificationRepository::toQualification)
        .optional();
  }

  @Override
  public List<Qualification> findByOrganisation(OrganisationId organisation) {
    return jdbc.sql(
            """
            SELECT id, organisation_id, name FROM qualification
            WHERE organisation_id = :organisation ORDER BY name
            """)
        .param("organisation", organisation.value())
        .query(JdbcQualificationRepository::toQualification)
        .list();
  }

  private static Qualification toQualification(ResultSet row, int rowNumber) throws SQLException {
    return new Qualification(
        new QualificationId(row.getObject("id", UUID.class)),
        new OrganisationId(row.getObject("organisation_id", UUID.class)),
        row.getString("name"));
  }
}
