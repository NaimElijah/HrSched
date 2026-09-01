package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/**
 * Driving port: administering the organisations the platform serves (requirement F13).
 *
 * <p>Onboarding and listing belong to the {@link Operator} alone, and that is a different shape of
 * check from every other use case here. Elsewhere the question is which {@code Role} a tenant user
 * holds; here it is whether the actor belongs to a tenant at all. An organisation is not created
 * from inside itself, and the list of tenants is the one collection no tenant may see.
 *
 * <p>Reading a single organisation is the exception: a tenant may read its own, which {@code
 * Actor.requireAccessTo} already expresses exactly.
 */
public interface ManageOrganisationsUseCase {

  /**
   * Brings a new organisation onto the platform.
   *
   * @param actor whoever is onboarding it; must be the platform operator
   * @param command what the organisation is called
   * @return the new organisation's identity, which becomes its tenant boundary
   * @throws io.github.naimelijah.hrsched.access.domain.model.NotThePlatformOperatorException if the
   *     actor belongs to a tenant
   * @throws io.github.naimelijah.hrsched.access.domain.model.InvalidOrganisationNameException if
   *     the name is blank
   */
  OrganisationId onboardOrganisation(Actor actor, OnboardOrganisationCommand command);

  /**
   * @param actor whoever is asking; must be the platform operator
   * @return every organisation on the platform, by name
   * @throws io.github.naimelijah.hrsched.access.domain.model.NotThePlatformOperatorException if the
   *     actor belongs to a tenant
   */
  List<PublishedOrganisation> findOrganisations(Actor actor);

  /**
   * @param actor whoever is asking; the operator, or a member of the organisation itself
   * @param organisation which one to read
   * @return that organisation, or empty if there is no such one
   * @throws io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException if a tenant
   *     actor names an organisation that is not theirs
   */
  Optional<PublishedOrganisation> findOrganisation(Actor actor, OrganisationId organisation);
}
