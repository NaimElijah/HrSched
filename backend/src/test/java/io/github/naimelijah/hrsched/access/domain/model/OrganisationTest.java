package io.github.naimelijah.hrsched.access.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  /** PostgreSQL's {@code text} cannot hold a NUL, so a name carrying one could never be stored. */
  @Test
  void GivenANameCarryingANul_WhenConstructingAnOrganisation_ThenItIsRejected() {
    assertThatThrownBy(() -> new Organisation(OrganisationId.newId(), "Acme\0Logistics"))
        .isInstanceOf(InvalidOrganisationNameException.class);
  }

  /**
   * A lone surrogate has no UTF-8 form, so the driver would store {@code ?} in its place: a name
   * nobody typed. High and low surrogates both, wherever they sit.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\ud800Acme", "Acme\ud800", "Ac\udc00me", "\ud800"})
  void GivenANameCarryingAnUnpairedSurrogate_WhenConstructingAnOrganisation_ThenItIsRejected(
      String name) {
    assertThatThrownBy(() -> new Organisation(OrganisationId.newId(), name))
        .isInstanceOf(InvalidOrganisationNameException.class);
  }

  /** A surrogate pair is one character, which UTF-8 holds, so it is not refused with the lone. */
  @Test
  void GivenANameCarryingASurrogatePair_WhenConstructingAnOrganisation_ThenItIsAccepted() {
    assertThat(new Organisation(OrganisationId.newId(), "Acme 😀").name()).isEqualTo("Acme 😀");
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
