package io.github.naimelijah.hrsched.access.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import org.junit.jupiter.api.Test;

/** Specification of the organisation entity. */
class OrganisationTest {

  @Test
  void GivenANonBlankName_WhenConstructingAnOrganisation_ThenItIsAccepted() {
    Organisation organisation = new Organisation(OrganisationId.newId(), "Acme Logistics");

    assertThat(organisation.name()).isEqualTo("Acme Logistics");
  }

  @Test
  void GivenABlankName_WhenConstructingAnOrganisation_ThenItIsRejected() {
    assertThatThrownBy(() -> new Organisation(OrganisationId.newId(), "  "))
        .isInstanceOf(InvalidOrganisationNameException.class);
  }

  @Test
  void GivenANullName_WhenConstructingAnOrganisation_ThenItIsRejected() {
    assertThatThrownBy(() -> new Organisation(OrganisationId.newId(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenTwoOrganisationsSharingAName_WhenComparingThem_ThenIdentityDecides() {
    OrganisationId id = OrganisationId.newId();

    assertThat(new Organisation(id, "Acme")).isEqualTo(new Organisation(id, "Acme Ltd"));
    assertThat(new Organisation(OrganisationId.newId(), "Acme"))
        .isNotEqualTo(new Organisation(OrganisationId.newId(), "Acme"));
  }
}
