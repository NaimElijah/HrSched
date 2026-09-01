package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import org.junit.jupiter.api.Test;

/**
 * Specification of what the organisation store promises.
 *
 * <p>The only repository here whose reads are not scoped by tenant, because it is the register of
 * tenants. What it does promise is ordering — by name, so the operator's listing is stable — and
 * that saving under an existing identity replaces rather than duplicates.
 */
class InMemoryOrganisationRepositoryTest {

  private final InMemoryOrganisationRepository organisations = new InMemoryOrganisationRepository();

  @Test
  void GivenAnOrganisation_WhenSaved_ThenItIsFoundByItsIdentity() {
    OrganisationId id = OrganisationId.newId();

    organisations.save(new Organisation(id, "Acme"));

    assertThat(organisations.findById(id)).map(Organisation::name).contains("Acme");
  }

  @Test
  void GivenNoSuchOrganisation_WhenLookedUp_ThenNothingIsReturned() {
    organisations.save(new Organisation(OrganisationId.newId(), "Acme"));

    assertThat(organisations.findById(OrganisationId.newId())).isEmpty();
  }

  @Test
  void GivenSeveralOrganisations_WhenListed_ThenTheyComeBackByName() {
    organisations.save(new Organisation(OrganisationId.newId(), "Zenith"));
    organisations.save(new Organisation(OrganisationId.newId(), "Acme"));
    organisations.save(new Organisation(OrganisationId.newId(), "Meridian"));

    assertThat(organisations.findAll())
        .extracting(Organisation::name)
        .containsExactly("Acme", "Meridian", "Zenith");
  }

  @Test
  void GivenNothingSaved_WhenListed_ThenNothingIsReturned() {
    assertThat(organisations.findAll()).isEmpty();
  }

  /**
   * An organisation is an entity: equality is by identity, so re-saving the same identity under a
   * new name is a rename, not a second tenant.
   */
  @Test
  void GivenAnOrganisationSavedTwice_WhenListed_ThenThereIsOneOfIt() {
    OrganisationId id = OrganisationId.newId();

    organisations.save(new Organisation(id, "Acme"));
    organisations.save(new Organisation(id, "Acme Holdings"));

    assertThat(organisations.findAll())
        .extracting(Organisation::name)
        .containsExactly("Acme Holdings");
  }
}
