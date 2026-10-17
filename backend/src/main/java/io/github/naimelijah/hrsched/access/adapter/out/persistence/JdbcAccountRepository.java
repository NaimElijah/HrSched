package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.EmployeeAlreadyLinkedException;
import io.github.naimelijah.hrsched.access.domain.model.Membership;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInNameTakenException;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Driven adapter storing accounts in PostgreSQL.
 *
 * <p>Every read but two filters on {@code organisation_id}, so the platform operator — whose row
 * carries none — is invisible to a tenant's own administration rather than merely hidden by a use
 * case. The exceptions are {@link #findBySignInName(SignInName)}, which sign-in needs because it
 * has only a name to go on, and {@link #findForAuthentication(UserId)}, which needs only the
 * identity a token names; the port says why each is safe.
 *
 * <p>Two different collisions arrive as the same SQLState, so this adapter tells them apart by the
 * name of the constraint that failed. The driver is a runtime-only dependency (ADR-004), so
 * PostgreSQL's own {@code getServerErrorMessage().getConstraint()} is not on the compile classpath;
 * the constraint's name is matched inside the message instead. What is matched is the name this
 * repository's own migration gave it, never the prose around it.
 */
@Repository
public class JdbcAccountRepository implements AccountRepository {

  /** PostgreSQL's {@code unique_violation}: some unique constraint or index refused the row. */
  private static final String UNIQUE_VIOLATION = "23505";

  /** The platform-wide unique constraint on the sign-in name, named in {@code V3__accounts.sql}. */
  private static final String SIGN_IN_NAME_CONSTRAINT = "account_sign_in_name_unique";

  /** The per-organisation unique index on the employee link, named in {@code V3__accounts.sql}. */
  private static final String EMPLOYEE_LINK_CONSTRAINT = "account_one_per_employee";

  /**
   * Names what the administration lock guards, so its key has a namespace of its own rather than a
   * number somebody chose. Distinct from the publication lock's scope, so the two can never
   * collide: an organisation publishing a roster must not wait on the same organisation creating an
   * account.
   */
  private static final String ADMINISTRATION_LOCK_SCOPE = "hrsched.account-administration";

  private static final String TENANT_KIND = "TENANT";
  private static final String OPERATOR_KIND = "OPERATOR";

  private static final String INSERT_ACCOUNT =
      """
      INSERT INTO account (id, sign_in_name, password_hash, kind, organisation_id, role,
                           employee_id, active, password_change_required, failed_sign_ins,
                           locked_until, created_at)
      VALUES (:id, :signInName, :passwordHash, :kind, :organisation, :role,
              :employee, :active, :passwordChangeRequired, :failedSignIns,
              :lockedUntil, :createdAt)
      """;

  /**
   * Replaces the stored version of an account that is already there, by identity, and only within
   * the organisation it belongs to.
   *
   * <p>{@code IS NOT DISTINCT FROM} rather than {@code =}, because the operator's row carries no
   * organisation and {@code NULL = NULL} is not true: an {@code =} would refuse every save of the
   * operator. A failed sign-in saves it, and so does every password change, the bootstrapped
   * operator's forced first change included. {@code account_membership_consistent} ties a missing
   * organisation to the operator's kind, so the guard also keeps an account from changing kind, and
   * neither column is in the {@code SET}: once the guard holds, neither can differ.
   */
  private static final String ON_CONFLICT_REPLACE =
      """
      ON CONFLICT (id) DO UPDATE SET sign_in_name = EXCLUDED.sign_in_name,
                                     password_hash = EXCLUDED.password_hash,
                                     role = EXCLUDED.role,
                                     employee_id = EXCLUDED.employee_id,
                                     active = EXCLUDED.active,
                                     password_change_required = EXCLUDED.password_change_required,
                                     failed_sign_ins = EXCLUDED.failed_sign_ins,
                                     locked_until = EXCLUDED.locked_until,
                                     created_at = EXCLUDED.created_at
      WHERE account.organisation_id IS NOT DISTINCT FROM EXCLUDED.organisation_id
      """;

  private final JdbcClient jdbc;

  public JdbcAccountRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(Account account) {
    try {
      store(account);
    } catch (DataAccessException refused) {
      throw translate(account, refused);
    }
  }

  @Override
  public boolean saveIfAbsent(Account account) {
    Optional<TenantMembership> tenant = tenantMembershipOf(account);
    try {
      return jdbc.sql(INSERT_ACCOUNT + "ON CONFLICT (sign_in_name) DO NOTHING")
              .params(parametersOf(account, tenant))
              .update()
          == 1;
    } catch (DataAccessException refused) {
      throw translate(account, refused);
    }
  }

  @Override
  public Optional<Account> find(OrganisationId organisation, UserId account) {
    return jdbc.sql(selectAccount() + " WHERE organisation_id = :organisation AND id = :id")
        .param("organisation", organisation.value())
        .param("id", account.value())
        .query(JdbcAccountRepository::toAccount)
        .optional();
  }

  @Override
  public List<Account> findAll(OrganisationId organisation) {
    return jdbc.sql(
            selectAccount() + " WHERE organisation_id = :organisation ORDER BY created_at, id")
        .param("organisation", organisation.value())
        .query(JdbcAccountRepository::toAccount)
        .list();
  }

  @Override
  public Optional<Account> findForAuthentication(UserId account) {
    return jdbc.sql(selectAccount() + " WHERE id = :id")
        .param("id", account.value())
        .query(JdbcAccountRepository::toAccount)
        .optional();
  }

  @Override
  public Optional<Account> findBySignInName(SignInName name) {
    return jdbc.sql(selectAccount() + " WHERE sign_in_name = :name")
        .param("name", name.value())
        .query(JdbcAccountRepository::toAccount)
        .optional();
  }

  /**
   * A transaction-scoped advisory lock keyed by organisation, taken the same way the publication
   * lock is (ADR-027) and in a namespace of its own.
   *
   * <p>Transaction-scoped because PostgreSQL releases it when the transaction ends, so no path can
   * forget to, and it stays correct behind a pooler that lends connections per transaction. Two
   * organisations whose identities hash alike only wait for each other; neither sees anything of
   * the other's.
   */
  @Override
  public void lockAdministrationOf(OrganisationId organisation) {
    // Taken in autocommit, a transaction-scoped lock ends with its own statement: the caller would
    // believe it was taking turns while nothing waited for anything.
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account administration can only be locked inside a transaction; outside one the lock"
              + " would be released as soon as it was taken");
    }
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:scope), hashtext(:organisation))")
        .param("scope", ADMINISTRATION_LOCK_SCOPE)
        .param("organisation", organisation.value().toString())
        .query()
        .listOfRows();
  }

  private void store(Account account) {
    int stored =
        jdbc.sql(INSERT_ACCOUNT + ON_CONFLICT_REPLACE)
            .params(parametersOf(account, tenantMembershipOf(account)))
            .update();
    requireStored(stored, account.id().value());
  }

  /**
   * The upsert's {@code WHERE} is the only thing standing between a re-used identity and another
   * tenant's row. It makes the mismatched case affect nothing rather than overwrite; this turns
   * that silence into a refusal, because a write that quietly did nothing is the worse of the two
   * failures.
   */
  private static void requireStored(int rows, UUID id) {
    if (rows == 0) {
      throw new IllegalStateException(
          "Refused to store %s: another organisation, or the operator, holds that identity"
              .formatted(id));
    }
  }

  private static Map<String, Object> parametersOf(
      Account account, Optional<TenantMembership> tenant) {
    // A HashMap rather than Map.of, because most of these are null for one kind of account or the
    // other and Map.of refuses a null value.
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("id", account.id().value());
    parameters.put("signInName", account.signInName().value());
    parameters.put("passwordHash", account.passwordHash().value());
    parameters.put("kind", tenant.isPresent() ? TENANT_KIND : OPERATOR_KIND);
    parameters.put(
        "organisation", tenant.map(member -> member.organisation().value()).orElse(null));
    parameters.put("role", tenant.map(member -> member.role().name()).orElse(null));
    parameters.put(
        "employee", tenant.flatMap(TenantMembership::employee).map(EmployeeId::value).orElse(null));
    parameters.put("active", account.active());
    parameters.put("passwordChangeRequired", account.passwordChangeRequired());
    parameters.put("failedSignIns", account.failedSignIns());
    parameters.put(
        "lockedUntil",
        account.lockedUntil().map(locked -> locked.atOffset(ZoneOffset.UTC)).orElse(null));
    parameters.put("createdAt", account.createdAt().atOffset(ZoneOffset.UTC));
    return parameters;
  }

  /**
   * Turns a refused write into the rule it broke, and leaves anything else alone. A failure this
   * does not recognise is not quietly renamed: it keeps being whatever it was.
   */
  private static RuntimeException translate(Account account, DataAccessException refused) {
    if (!(NestedExceptionUtils.getMostSpecificCause(refused) instanceof SQLException cause)
        || !UNIQUE_VIOLATION.equals(cause.getSQLState())
        || cause.getMessage() == null) {
      return refused;
    }
    if (cause.getMessage().contains(SIGN_IN_NAME_CONSTRAINT)) {
      return new SignInNameTakenException(refused);
    }
    if (cause.getMessage().contains(EMPLOYEE_LINK_CONSTRAINT)) {
      return tenantMembershipOf(account)
          .flatMap(TenantMembership::employee)
          .<RuntimeException>map(employee -> new EmployeeAlreadyLinkedException(employee, refused))
          .orElse(refused);
    }
    return refused;
  }

  private static Optional<TenantMembership> tenantMembershipOf(Account account) {
    return account.membership() instanceof TenantMembership tenant
        ? Optional.of(tenant)
        : Optional.empty();
  }

  private static String selectAccount() {
    return """
        SELECT id, sign_in_name, password_hash, kind, organisation_id, role, employee_id,
               active, password_change_required, failed_sign_ins, locked_until, created_at
        FROM account
        """;
  }

  private static Account toAccount(ResultSet row, int rowNumber) throws SQLException {
    return readAccount(row);
  }

  /**
   * Reads an account from a row, whatever query produced it.
   *
   * <p>Package-private so that {@code JdbcSessionRepository} can map the account half of its join
   * with this mapper rather than a second copy of it. Two mappers over one table would be two
   * things to keep in step with the next migration.
   *
   * @param row a row carrying every column of {@code account}
   * @return the account it describes
   * @throws SQLException if a column cannot be read
   */
  static Account readAccount(ResultSet row) throws SQLException {
    return new Account(
        new UserId(row.getObject("id", UUID.class)),
        new SignInName(row.getString("sign_in_name")),
        new PasswordHash(row.getString("password_hash")),
        toMembership(row),
        row.getBoolean("active"),
        row.getBoolean("password_change_required"),
        row.getInt("failed_sign_ins"),
        Optional.ofNullable(row.getObject("locked_until", OffsetDateTime.class))
            .map(OffsetDateTime::toInstant),
        instantOf(row, "created_at"));
  }

  private static Membership toMembership(ResultSet row) throws SQLException {
    if (OPERATOR_KIND.equals(row.getString("kind"))) {
      return new OperatorMembership();
    }
    return new TenantMembership(
        new OrganisationId(row.getObject("organisation_id", UUID.class)),
        Role.valueOf(row.getString("role")),
        Optional.ofNullable(row.getObject("employee_id", UUID.class)).map(EmployeeId::new));
  }

  private static Instant instantOf(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }
}
