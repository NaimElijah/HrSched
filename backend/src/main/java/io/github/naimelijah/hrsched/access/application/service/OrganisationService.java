package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.in.KnownOrganisationsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.ManageOrganisationsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.OnboardOrganisationCommand;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedOrganisation;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.access.application.port.out.OrganisationRepository;
import io.github.naimelijah.hrsched.access.domain.model.NotThePlatformOperatorException;
import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
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
  private final AuditTrail audit;

  public OrganisationService(OrganisationRepository organisations, AuditTrail audit) {
    this.organisations = Objects.requireNonNull(organisations, "organisations must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
  }

  @Override
  public OrganisationId onboardOrganisation(Actor actor, OnboardOrganisationCommand command) {
    requireOperator(actor);
    Organisation organisation = new Organisation(OrganisationId.newId(), command.name());
    organisations.save(organisation);
    audit.record(
        new AuditedChange(
            Optional.of(organisation.id()),
            actor.id(),
            AccessChange.ORGANISATION_ONBOARDED,
            organisation.id().value()));
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
