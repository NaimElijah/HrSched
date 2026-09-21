package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.in.KnownOrganisationsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.ManageOrganisationsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.OnboardOrganisationCommand;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedOrganisation;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.access.application.port.out.OrganisationRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.NotThePlatformOperatorException;
import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.PasswordRejection;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.access.domain.model.UnacceptablePasswordException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates the organisation use cases, and answers whether a claimed tenant exists.
 *
 * <p>Implements two ports because they are two audiences for one register: {@link
 * ManageOrganisationsUseCase} is what an operator drives, and {@link KnownOrganisationsUseCase} is
 * the single question the rest of the system asks of it.
 */
public class OrganisationService implements ManageOrganisationsUseCase, KnownOrganisationsUseCase {

  private final OrganisationRepository organisations;
  private final AccountRepository accounts;
  private final PasswordHasher passwords;
  private final PasswordPolicy passwordPolicy;
  private final AuditTrail audit;
  private final Clock clock;

  public OrganisationService(
      OrganisationRepository organisations,
      AccountRepository accounts,
      PasswordHasher passwords,
      PasswordPolicy passwordPolicy,
      AuditTrail audit,
      Clock clock) {
    this.organisations = Objects.requireNonNull(organisations, "organisations must not be null");
    this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
    this.passwords = Objects.requireNonNull(passwords, "passwords must not be null");
    this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Onboards an organisation together with the account that will administer it.
   *
   * <p>One act rather than two, and one transaction. An organisation nobody can administer is not
   * usable, and the operator is refused everything inside one (ADR-030), so there would be no way
   * to add an Admin afterwards. A refused account therefore leaves no organisation behind: the
   * transaction rolls back, and the operator can try again with another name.
   *
   * <p>The temporary password is checked against the policy, unlike the bootstrapped operator's.
   * That one is the deployment's own choice and unavoidable (ADR-029); this one is typed by
   * somebody onboarding a customer, and handing a new Admin a password the policy would refuse
   * would mean their first act had to be a password change they could not make.
   */
  @Override
  public OrganisationId onboardOrganisation(Actor actor, OnboardOrganisationCommand command) {
    requireOperator(actor);
    Optional<PasswordRejection> broken =
        passwordPolicy.assess(command.firstAdminTemporaryPassword());
    if (broken.isPresent()) {
      throw new UnacceptablePasswordException(broken.get());
    }
    Organisation organisation = new Organisation(OrganisationId.newId(), command.name());
    organisations.save(organisation);
    Account firstAdmin =
        new Account(
            UserId.newId(),
            new SignInName(command.firstAdminSignInName()),
            passwords.hash(command.firstAdminTemporaryPassword()),
            new TenantMembership(organisation.id(), Role.ADMIN, Optional.empty()),
            true,
            clock.instant());
    // save rather than saveIfAbsent: a name already taken must refuse the whole onboarding, not
    // quietly leave an organisation with somebody else's Admin attached to it.
    accounts.save(firstAdmin);
    audit.record(
        new AuditedChange(
            Optional.of(organisation.id()),
            actor.id(),
            AccessChange.ORGANISATION_ONBOARDED,
            organisation.id().value()));
    audit.record(
        new AuditedChange(
            Optional.of(organisation.id()),
            actor.id(),
            AccessChange.ACCOUNT_CREATED,
            firstAdmin.id().value()));
    return organisation.id();
  }

  @Override
  public List<PublishedOrganisation> findOrganisations(Actor actor) {
    requireOperator(actor);
    return organisations.findAll().stream().map(OrganisationService::publish).toList();
  }

  @Override
  public Optional<PublishedOrganisation> findOrganisation(
      Actor actor, OrganisationId organisation) {
    // The one place an operator is let past the tenant boundary, and it is the register rather than
    // anything inside a tenant: administering organisations is what an operator is for (F13), and
    // listing them already tells them which exist. Everywhere else requireAccessTo refuses an
    // operator outright, which is the whole of ADR-030.
    if (!(actor instanceof Operator)) {
      actor.requireAccessTo(organisation);
    }
    return organisations.findById(organisation).map(OrganisationService::publish);
  }

  @Override
  public boolean isKnown(OrganisationId organisation) {
    Objects.requireNonNull(organisation, "organisation must not be null");
    return organisations.findById(organisation).isPresent();
  }

  /**
   * The inverse of every other guard here: not "which role does this tenant user hold", but "does
   * this actor belong to a tenant at all". A tenant Admin fails it however privileged they are
   * inside their own organisation.
   *
   * @param actor whoever is acting
   * @throws NotThePlatformOperatorException if the actor belongs to a tenant
   */
  private static void requireOperator(Actor actor) {
    if (!(actor instanceof Operator)) {
      throw new NotThePlatformOperatorException(actor.id());
    }
  }

  private static PublishedOrganisation publish(Organisation organisation) {
    return new PublishedOrganisation(organisation.id(), organisation.name());
  }
}
