package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import java.sql.SQLException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;

/**
 * Tells which unique constraint a refused write broke, so an adapter turns into a domain refusal
 * only the violation it means, and lets any other surface as the store reported it.
 *
 * <p>The constraint is matched by the name its migration gave it, inside the driver's message, as
 * {@code JdbcAccountRepository} does: the driver is a runtime-only dependency (ADR-004), so
 * PostgreSQL's own {@code getServerErrorMessage().getConstraint()} is not on the compile classpath.
 */
final class UniqueViolations {

  /** PostgreSQL's SQLSTATE for {@code unique_violation}. */
  private static final String UNIQUE_VIOLATION = "23505";

  private UniqueViolations() {}

  /**
   * @param refused what the store threw
   * @param constraint the name a migration gave the unique constraint or index
   * @return whether {@code refused} is a unique violation of that constraint and no other
   */
  static boolean isViolationOf(DataAccessException refused, String constraint) {
    return NestedExceptionUtils.getMostSpecificCause(refused) instanceof SQLException cause
        && UNIQUE_VIOLATION.equals(cause.getSQLState())
        && cause.getMessage() != null
        && cause.getMessage().contains(constraint);
  }
}
