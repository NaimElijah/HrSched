package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorCommand;
import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.ChangeMembershipCommand;
import io.github.naimelijah.hrsched.access.application.port.in.CreateAccountCommand;
import io.github.naimelijah.hrsched.access.application.port.in.ManageAccountsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedAccount;
import io.github.naimelijah.hrsched.access.application.port.in.ResetPasswordCommand;
import io.github.naimelijah.hrsched.access.application.port.in.SetAccountActiveCommand;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.access.application.port.out.EmployeeDirectory;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.application.port.out.SessionRepository;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.CannotResetOwnPasswordException;
import io.github.naimelijah.hrsched.access.domain.model.LastActiveAdminException;
import io.github.naimelijah.hrsched.access.domain.model.NotAnEmployeeHereException;
import io.github.naimelijah.hrsched.access.domain.model.OperatorBelongsToNoOrganisationException;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.OperatorNameHeldByATenantException;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.PasswordRejection;
import io.github.naimelijah.hrsched.access.domain.model.SessionRevocation;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.access.domain.model.UnacceptablePasswordException;
import io.github.naimelijah.hrsched.access.domain.model.UnknownAccountException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the account use cases: the platform's first account, and every account after it.
 *
 * <p>Provisioning runs top-down (ADR-029). The operator is bootstrapped from configuration, an
 * organisation's first Admin is created when it is onboarded, and every other account is created by
 * an Admin here. Apart from {@link #ensureOperator}, which runs at startup before anybody can sign
 * in, nothing in this class can be reached without an account that already exists.
 *
 * <p>The two methods that can take an active Admin away, {@link #changeMembership} and {@link
 * #setAccountActive}, take the organisation's administration lock before they read anything, and
 * the reason is {@link #requireOrganisationKeepsAnAdmin}: that rule is decided by reading and then
 * writing, so two Admins each disabling one of the last two would both read an organisation that
 * still had two and leave it with none. Creating an account and resetting a password take no Admin
 * away, so they take no lock.
 */
public class AccountService implements BootstrapOperatorUseCase, ManageAccountsUseCase {

  private static final Set<Role> MAY_ADMINISTER = Set.of(Role.ADMIN);

  private final AccountRepository accounts;
  private final SessionRepository sessions;
  private final EmployeeDirectory employees;
  private final PasswordHasher passwords;
  private final PasswordPolicy passwordPolicy;
  private final AuditTrail audit;
  private final Clock clock;

  public AccountService(
      AccountRepository accounts,
      SessionRepository sessions,
      EmployeeDirectory employees,
      PasswordHasher passwords,
      PasswordPolicy passwordPolicy,
      AuditTrail audit,
      Clock clock) {
    this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
    this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    this.employees = Objects.requireNonNull(employees, "employees must not be null");
    this.passwords = Objects.requireNonNull(passwords, "passwords must not be null");
    this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
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
   *
   * <p>A name the operator already holds is left alone. A name a tenant holds is refused: no
   * operator can be created under it, and reporting one as present there would be false.
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
      requireHeldByTheOperator(operator.signInName());
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

  @Override
  public UserId createAccount(Actor actor, CreateAccountCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    authorise(actor, command.organisation());
    requirePasswordSatisfiesPolicy(command.temporaryPassword());
    requireEmployedHere(actor, command.organisation(), command.employee());
    Account created =
        new Account(
            UserId.newId(),
            new SignInName(command.signInName()),
            passwords.hash(command.temporaryPassword()),
            new TenantMembership(command.organisation(), command.role(), command.employee()),
            true,
            clock.instant());
    // save rather than saveIfAbsent: a name already taken must refuse the request, not quietly
    // leave the Admin believing they created an account that is somebody else's.
    accounts.save(created);
    recorded(actor, command.organisation(), AccessChange.ACCOUNT_CREATED, created.id());
    return created.id();
  }

  @Override
  public List<PublishedAccount> findAccounts(Actor actor, OrganisationId organisation) {
    authorise(actor, organisation);
    Instant now = clock.instant();
    return accounts.findAll(organisation).stream().map(account -> publish(account, now)).toList();
  }

  @Override
  public void changeMembership(Actor actor, ChangeMembershipCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    authorise(actor, command.organisation());
    accounts.lockAdministrationOf(command.organisation());
    Account account = load(command.organisation(), command.account());
    if (command.role() != Role.ADMIN) {
      requireOrganisationKeepsAnAdmin(command.organisation(), account);
    }
    requireEmployedHere(actor, command.organisation(), command.employee());
    accounts.save(account.withMembership(command.role(), command.employee()));
    recorded(
        actor, command.organisation(), AccessChange.ACCOUNT_MEMBERSHIP_CHANGED, command.account());
  }

  @Override
  public void setAccountActive(Actor actor, SetAccountActiveCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    authorise(actor, command.organisation());
    accounts.lockAdministrationOf(command.organisation());
    Account account = load(command.organisation(), command.account());
    if (!command.active()) {
      requireOrganisationKeepsAnAdmin(command.organisation(), account);
    }
    accounts.save(command.active() ? account.enabled() : account.disabled());
    if (!command.active()) {
      // Ending the sessions is the disabling. Without it the account keeps working until its
      // access token expires, and a credential that still works is not disabled.
      sessions.revokeEverySessionOf(
          command.account(), SessionRevocation.ACCOUNT_DISABLED, clock.instant());
    }
    recorded(
        actor,
        command.organisation(),
        command.active() ? AccessChange.ACCOUNT_ENABLED : AccessChange.ACCOUNT_DISABLED,
        command.account());
  }

  @Override
  public void resetPassword(Actor actor, ResetPasswordCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    authorise(actor, command.organisation());
    if (actor.id().equals(command.account())) {
      throw new CannotResetOwnPasswordException(actor.id());
    }
    requirePasswordSatisfiesPolicy(command.temporaryPassword());
    Account account = load(command.organisation(), command.account());
    accounts.save(
        account.unlocked().withPassword(passwords.hash(command.temporaryPassword()), true));
    // Whoever knew the old password may still hold a session opened with it, and that is the case
    // a reset is usually a response to.
    sessions.revokeEverySessionOf(
        command.account(), SessionRevocation.PASSWORD_RESET, clock.instant());
    recorded(actor, command.organisation(), AccessChange.ACCOUNT_PASSWORD_RESET, command.account());
  }

  /**
   * The rule that keeps an organisation administrable.
   *
   * <p>Checked against the stored accounts rather than a count kept somewhere, because what must be
   * true is about the organisation as it is now. An account that is not an active Admin cannot be
   * the last one, so the question is only asked of those that are.
   *
   * @param organisation whose Admins are counted
   * @param account the account about to stop being an active Admin
   * @throws LastActiveAdminException if no other active Admin would remain
   */
  private void requireOrganisationKeepsAnAdmin(OrganisationId organisation, Account account) {
    if (!isAdmin(account) || !account.active()) {
      return;
    }
    boolean anotherRemains =
        accounts.findAll(organisation).stream()
            .filter(candidate -> !candidate.id().equals(account.id()))
            .filter(Account::active)
            .anyMatch(AccountService::isAdmin);
    if (!anotherRemains) {
      throw new LastActiveAdminException(account.id());
    }
  }

  private static boolean isAdmin(Account account) {
    return account.membership() instanceof TenantMembership tenant && tenant.role() == Role.ADMIN;
  }

  private void requirePasswordSatisfiesPolicy(String candidate) {
    Optional<PasswordRejection> broken = passwordPolicy.assess(candidate);
    if (broken.isPresent()) {
      throw new UnacceptablePasswordException(broken.get());
    }
  }

  /**
   * Read only after the insert was refused, so a holder that is then not found was removed in
   * between. That is a fault rather than an operator present, and it fails loudly.
   *
   * @throws OperatorNameHeldByATenantException if the account holding the name is not the operator
   */
  private void requireHeldByTheOperator(SignInName name) {
    Account holder =
        accounts
            .findBySignInName(name)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "The operator's sign-in name was held a moment ago and is held by no"
                            + " account now"));
    if (!(holder.membership() instanceof OperatorMembership)) {
      throw new OperatorNameHeldByATenantException(holder.id());
    }
  }

  /**
   * Asks the workforce, rather than trusting the identifier. An unchecked link would let an Admin
   * point an account at any UUID, and one that happened to name another organisation's employee
   * would grant its holder that person's self-service (ADR-012).
   */
  private void requireEmployedHere(
      Actor actor, OrganisationId organisation, Optional<EmployeeId> employee) {
    employee.ifPresent(
        linked -> {
          if (!employees.isEmployedHere(actor, organisation, linked)) {
            throw new NotAnEmployeeHereException(linked);
          }
        });
  }

  /**
   * Held from this read until the change commits, because the change stores the account whole: a
   * failed sign-in counted on a copy read before this change committed would otherwise undo it
   * (#33, ADR-041). Every caller is a change; reads use {@code find} directly.
   */
  private Account load(OrganisationId organisation, UserId account) {
    return accounts
        .findForUpdate(Optional.of(organisation), account)
        .orElseThrow(() -> new UnknownAccountException(account));
  }

  /**
   * The operator branch is unreachable rather than unhandled: every read here is scoped to an
   * organisation, and the operator belongs to none, so no scoped read can return it. The switch
   * says so explicitly instead of casting and trusting.
   */
  private static PublishedAccount publish(Account account, Instant now) {
    return switch (account.membership()) {
      case TenantMembership tenant ->
          new PublishedAccount(
              account.id(),
              account.signInName().value(),
              tenant.role(),
              tenant.employee(),
              account.active(),
              account.passwordChangeRequired(),
              account.isLockedAt(now),
              account.createdAt());
      case OperatorMembership operator ->
          throw new OperatorBelongsToNoOrganisationException(account.id());
    };
  }

  /**
   * Records a change that has already been saved, never before it: an entry for something that then
   * failed to store is a record of something that did not happen.
   */
  private void recorded(
      Actor actor, OrganisationId organisation, AccessChange change, UserId subject) {
    audit.record(new AuditedChange(Optional.of(organisation), actor.id(), change, subject.value()));
  }

  private static void authorise(Actor actor, OrganisationId organisation) {
    actor.requireAccessTo(organisation).requireRoleIn(MAY_ADMINISTER);
  }
}
