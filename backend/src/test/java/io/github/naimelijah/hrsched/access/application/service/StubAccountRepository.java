package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores accounts in a list, and keeps the two rules a service depends on.
 *
 * <p>Hand-written rather than mocked, like every other double here: insert-if-absent and the tenant
 * predicate are rules this has to keep for a test to mean anything, and a mock would only replay
 * whatever it was told.
 *
 * <p>Named without a {@code Test} suffix so Surefire does not try to run it.
 */
final class StubAccountRepository implements AccountRepository {

  private final List<Account> stored = new ArrayList<>();

  /**
   * @param account an account that already exists before the test does anything
   * @return the same account, so a test can arrange and name it in one line
   */
  Account given(Account account) {
    stored.add(account);
    return account;
  }

  @Override
  public void save(Account account) {
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

  @Override
  public Optional<Account> findBySignInName(SignInName name) {
    return stored.stream().filter(candidate -> candidate.signInName().equals(name)).findFirst();
  }

  @Override
  public void lockAdministrationOf(OrganisationId organisation) {
    // Nothing to serialise: this fake is never shared between threads.
  }
}
