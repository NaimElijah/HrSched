package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import java.sql.SQLException;
import java.util.Optional;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;

/**
 * Tells what a refused write ran into, so an adapter turns into a domain refusal only the violation
 * it means, and lets any other surface as the store reported it.
 *
 * <p>Read from the driver's exception rather than by exception type, because Spring maps some
 * violations, an exclusion violation among them, to nothing more specific than a data integrity
 * violation, which says nothing about which rule. A constraint is matched by the name its migration
 * gave it, inside the driver's message, as {@code JdbcAccountRepository} does: the driver is a
 * runtime-only dependency (ADR-004), so PostgreSQL's own {@code
 * getServerErrorMessage().getConstraint()} is not on the compile classpath.
 */
final class StoreRefusals {

  /** PostgreSQL's SQLSTATE for {@code unique_violation}. */
  static final String UNIQUE_VIOLATION = "23505";

  private StoreRefusals() {}

  /**
   * @param refused what the store threw
   * @param sqlState the SQLSTATE PostgreSQL reports for the kind of violation meant
   * @param constraint the name a migration gave the constraint or index
   * @return whether {@code refused} is a violation of that kind, of that constraint and no other
   */
  static boolean isViolationOf(DataAccessException refused, String sqlState, String constraint) {
    return reportedAs(refused, sqlState)
        .map(SQLException::getMessage)
        .filter(message -> message.contains(constraint))
        .isPresent();
  }

  /**
   * For a refusal that names no constraint, such as a deadlock.
   *
   * @param refused what the store threw
   * @param sqlState the SQLSTATE meant
   * @return whether the store reported {@code refused} with that SQLSTATE
   */
  static boolean isReportedAs(DataAccessException refused, String sqlState) {
    return reportedAs(refused, sqlState).isPresent();
  }

  private static Optional<SQLException> reportedAs(DataAccessException refused, String sqlState) {
    return NestedExceptionUtils.getMostSpecificCause(refused) instanceof SQLException cause
            && sqlState.equals(cause.getSQLState())
        ? Optional.of(cause)
        : Optional.empty();
  }
}
