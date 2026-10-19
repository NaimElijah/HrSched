package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.EmployeeAlreadyLinkedException;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInNameTakenException;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores accounts in a list, and keeps the two rules a service depends on.
 *
 * <p>Hand-written rather than mocked, like every other double here: unique sign-in names, one
 * account per employee, insert-if-absent and the tenant predicate are rules this has to keep for a
 * test to mean anything, and a mock would only replay whatever it was told.
 *
 * <p>It also remembers whether the administration lock was taken, because "this use case takes the
 * lock" is a claim about behaviour and the only honest way to assert it against a fake is to ask
 * the fake.
 *
 * <p>Named without a {@code Test} suffix so Surefire does not try to run it.
 */
final class StubAccountRepository implements AccountRepository {

  private final List<Account> stored = new ArrayList<>();
  private final List<OrganisationId> locked = new ArrayList<>();
  private final List<UserId> heldForUpdate = new ArrayList<>();
  private Optional<Account> readBeforeAChange = Optional.empty();

  /**
   * @param account an account that already exists before the test does anything
   * @return the same account, so a test can arrange and name it in one line
   */
  Account given(Account account) {
    stored.add(account);
    return account;
  }

  /**
   * Refuses what {@code JdbcAccountRepository} refuses: a taken sign-in name, a second account for
   * one employee, and an identity another organisation holds, or the operator does. Comparing the
   * two {@code Optional} organisations is the adapter's {@code IS NOT DISTINCT FROM}: the
   * operator's is empty, and equal only to another empty one.
   */
  @Override
  public void save(Account account) {
    stored.stream()
        .filter(existing -> existing.id().equals(account.id()))
        .filter(existing -> !existing.organisation().equals(account.organisation()))
        .findAny()
        .ifPresent(
            existing -> {
              throw new IllegalStateException(
                  "this fake holds that identity in another organisation, or as the operator");
            });
    findBySignInName(account.signInName())
        .filter(existing -> !existing.id().equals(account.id()))
        .ifPresent(
            existing -> {
              // The exception carries a cause because the real adapter has one - the integrity
              // violation the driver reported. Here the collision is found by looking, so the
              // cause says that rather than pretending to be a database.
              throw new SignInNameTakenException(
                  new IllegalStateException("this fake already holds that sign-in name"));
            });
    linkedEmployeeOf(account)
        .flatMap(employee -> alreadyLinked(account, employee))
        .ifPresent(
            employee -> {
              throw new EmployeeAlreadyLinkedException(
                  employee, new IllegalStateException("this fake already links that employee"));
            });
    stored.removeIf(existing -> existing.id().equals(account.id()));
    stored.add(account);
  }

  @Override
  public boolean saveIfAbsent(Account account) {
    if (findBySignInName(account.signInName()).isPresent()) {
      return false;
    }
    stored.add(account);
    return true;
  }

  /** One account per employee, and only within the organisation that employs them. */
  private Optional<EmployeeId> alreadyLinked(Account account, EmployeeId employee) {
    return stored.stream()
        .filter(existing -> !existing.id().equals(account.id()))
        .filter(existing -> existing.organisation().equals(account.organisation()))
        .filter(existing -> linkedEmployeeOf(existing).filter(employee::equals).isPresent())
        .findFirst()
        .map(existing -> employee);
  }

  private static Optional<EmployeeId> linkedEmployeeOf(Account account) {
    return account.membership() instanceof TenantMembership tenant
        ? tenant.employee()
        : Optional.empty();
  }

  @Override
  public Optional<Account> find(OrganisationId organisation, UserId account) {
    return stored.stream()
        .filter(candidate -> candidate.organisation().filter(organisation::equals).isPresent())
        .filter(candidate -> candidate.id().equals(account))
        .findFirst();
  }

  @Override
  public List<Account> findAll(OrganisationId organisation) {
    return stored.stream()
        .filter(candidate -> candidate.organisation().filter(organisation::equals).isPresent())
        .toList();
  }

  @Override
  public Optional<Account> findForAuthentication(UserId account) {
    return stored.stream().filter(candidate -> candidate.id().equals(account)).findFirst();
  }

  /**
   * Holds nothing, for the reason {@link #lockAdministrationOf} holds nothing, but records the read
   * so a test can see a change hold the account it is about to store. That the read really makes a
   * second change wait is proven against PostgreSQL, in {@code JdbcAccountRepositoryTest}.
   */
  @Override
  public Optional<Account> findForUpdate(Optional<OrganisationId> organisation, UserId account) {
    heldForUpdate.add(account);
    return stored.stream()
        .filter(candidate -> candidate.organisation().equals(organisation))
        .filter(candidate -> candidate.id().equals(account))
        .findFirst();
  }

  /**
   * @return every account read for update so far, in the order they were read
   */
  List<UserId> heldForUpdate() {
    return List.copyOf(heldForUpdate);
  }

  @Override
  public Optional<Account> findBySignInName(SignInName name) {
    return readBeforeAChange
        .filter(earlier -> earlier.signInName().equals(name))
        .or(
            () ->
                stored.stream()
                    .filter(candidate -> candidate.signInName().equals(name))
                    .findFirst());
  }

  /**
   * Makes the lookup by sign-in name answer with an earlier copy of an account than the one stored,
   * standing in for a read made before a concurrent change committed. One thread cannot interleave
   * two transactions, so this is how a test reaches the race sign-in has to survive: its unlocked
   * read is stale by the time it holds the account (ADR-041).
   *
   * @param earlier the account as it was before the change the test then stores
   */
  void signInReadsBeforeAChange(Account earlier) {
    readBeforeAChange = Optional.of(earlier);
  }

  /**
   * @return every organisation whose administration was locked, in order
   */
  List<OrganisationId> lockedAdministrations() {
    return List.copyOf(locked);
  }

  @Override
  public void lockAdministrationOf(OrganisationId organisation) {
    // Nothing to serialise: this fake is never shared between threads. Remembering the call is the
    // point - a use case that stopped taking the lock would otherwise still pass every test.
    locked.add(organisation);
  }
}
