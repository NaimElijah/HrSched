package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import io.github.naimelijah.hrsched.access.application.port.out.OrganisationRepository;
import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing organisations in PostgreSQL.
 *
 * <p>Unlike its siblings there is no organisation to scope a read by: this is the register of
 * tenants, so {@link #findAll()} is meant to cross every one of them and the guard is the use case
 * above it.
 *
 * <p>This is the table every other table's tenant column points at, and no such foreign key
 * cascades, so an organisation that still owns anything cannot be deleted from under its data.
 */
@Repository
public class JdbcOrganisationRepository implements OrganisationRepository {

  /** PostgreSQL's {@code unique_violation}: some unique constraint or index refused the row. */
  private static final String UNIQUE_VIOLATION = "23505";

  /** The primary key {@code V1__initial_schema.sql} leaves PostgreSQL to name. */
  private static final String PRIMARY_KEY = "organisation_pkey";

  private final JdbcClient jdbc;

  public JdbcOrganisationRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /**
   * A plain insert: an organisation has no owner to guard the write by, so an identity already
   * stored is refused by the primary key rather than renaming the tenant that holds it.
   *
   * <p>That refusal is matched by the primary key's name inside the driver's message, as {@code
   * JdbcAccountRepository} matches its constraints, because the driver is a runtime-only dependency
   * (ADR-004). Any other refusal is left as it was.
   */
  @Override
  public void save(Organisation organisation) {
    try {
      jdbc.sql("INSERT INTO organisation (id, name) VALUES (:id, :name)")
          .param("id", organisation.id().value())
          .param("name", organisation.name())
          .update();
    } catch (DataAccessException refused) {
      throw translate(organisation.id(), refused);
    }
  }

  @Override
  public Optional<Organisation> findById(OrganisationId id) {
    return jdbc.sql("SELECT id, name FROM organisation WHERE id = :id")
        .param("id", id.value())
        .query(JdbcOrganisationRepository::toOrganisation)
        .optional();
  }

  /** By name, then by identity: organisations may share a name, and the order must not change. */
  @Override
  public List<Organisation> findAll() {
    return jdbc.sql("SELECT id, name FROM organisation ORDER BY name, id")
        .query(JdbcOrganisationRepository::toOrganisation)
        .list();
  }

  /**
   * Turns the primary key's refusal into the fault it is, naming the identity and never the name,
   * and leaves anything else alone.
   */
  private static RuntimeException translate(OrganisationId id, DataAccessException refused) {
    if (NestedExceptionUtils.getMostSpecificCause(refused) instanceof SQLException cause
        && UNIQUE_VIOLATION.equals(cause.getSQLState())
        && cause.getMessage() != null
        && cause.getMessage().contains(PRIMARY_KEY)) {
      return new IllegalStateException(
          "Refused to store organisation %s: that identity is already stored".formatted(id.value()),
          refused);
    }
    return refused;
  }

  private static Organisation toOrganisation(ResultSet row, int rowNumber) throws SQLException {
    return new Organisation(
        new OrganisationId(row.getObject("id", java.util.UUID.class)), row.getString("name"));
  }
}
