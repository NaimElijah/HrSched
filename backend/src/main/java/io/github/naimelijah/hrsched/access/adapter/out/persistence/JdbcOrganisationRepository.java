package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import io.github.naimelijah.hrsched.access.application.port.out.OrganisationRepository;
import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing organisations in PostgreSQL.
 *
 * <p>Unlike its siblings there is no organisation to scope a read by: this is the register of
 * tenants, so {@link #findAll()} is meant to cross every one of them and the guard is the use case
 * above it.
 *
 * <p>This is the table every other table's tenant column points at, so a row here going missing is
 * not a lost record but a tenant that stops existing. That it is now durable is the sharpest thing
 * ADR-004 fixed.
 */
@Repository
public class JdbcOrganisationRepository implements OrganisationRepository {

  private final JdbcClient jdbc;

  public JdbcOrganisationRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(Organisation organisation) {
    jdbc.sql(
            """
            INSERT INTO organisation (id, name) VALUES (:id, :name)
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name
            """)
        .param("id", organisation.id().value())
        .param("name", organisation.name())
        .update();
  }

  @Override
  public Optional<Organisation> findById(OrganisationId id) {
    return jdbc.sql("SELECT id, name FROM organisation WHERE id = :id")
        .param("id", id.value())
        .query(JdbcOrganisationRepository::toOrganisation)
        .optional();
  }

  @Override
  public List<Organisation> findAll() {
    return jdbc.sql("SELECT id, name FROM organisation ORDER BY name")
        .query(JdbcOrganisationRepository::toOrganisation)
        .list();
  }

  private static Organisation toOrganisation(ResultSet row, int rowNumber) throws SQLException {
    return new Organisation(
        new OrganisationId(row.getObject("id", java.util.UUID.class)), row.getString("name"));
  }
}
