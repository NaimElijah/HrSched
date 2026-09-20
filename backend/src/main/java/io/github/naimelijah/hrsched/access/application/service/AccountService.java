package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorCommand;
import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorUseCase;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates the account use cases.
 *
 * <p>Today that is one: making sure the platform has an operator. Every other account is created by
 * an account that already exists, so this is where the chain of provisioning starts.
 */
public class AccountService implements BootstrapOperatorUseCase {

  private final AccountRepository accounts;
  private final PasswordHasher passwords;
  private final AuditTrail audit;
  private final Clock clock;

  public AccountService(
      AccountRepository accounts, PasswordHasher passwords, AuditTrail audit, Clock clock) {
    this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
    this.passwords = Objects.requireNonNull(passwords, "passwords must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Creates the operator only if the name is free, and never touches an account that already holds
   * it. Whether the name is free and whether the row is written are one question asked of the
   * database, so two instances starting at once cannot both create it.
   *
   * <p>The configured password is deliberately not checked against any password policy. It is
   * chosen by whoever runs the deployment rather than submitted by a user, and it must be changed
   * at first sign-in, which is where the policy applies (ADR-029).
   */
  @Override
  public boolean ensureOperator(BootstrapOperatorCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    Account operator =
        new Account(
            UserId.newId(),
            new SignInName(command.signInName()),
            passwords.hash(command.temporaryPassword()),
            new OperatorMembership(),
            true,
            clock.instant());
    if (!accounts.saveIfAbsent(operator)) {
      return false;
    }
    // The operator is recorded as its own actor: nobody is signed in at startup, and attributing
    // the most privileged account on the platform to a placeholder identity would be worse than
    // attributing it to itself.
    audit.record(
        new AuditedChange(
            Optional.empty(),
            operator.id(),
            AccessChange.OPERATOR_BOOTSTRAPPED,
            operator.id().value()));
    return true;
  }
}
