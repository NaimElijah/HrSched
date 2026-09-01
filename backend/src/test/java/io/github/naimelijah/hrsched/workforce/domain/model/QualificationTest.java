package io.github.naimelijah.hrsched.workforce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The invariants a qualification owns.
 *
 * <p>An entity rather than a record: two organisations may both call something "Forklift" and mean
 * different training, so equality is identity and never the name.
 */
class QualificationTest {

  private static final OrganisationId ACME = OrganisationId.newId();

  @Test
  void GivenANonBlankName_WhenConstructingAQualification_ThenItIsAccepted() {
    assertThat(new Qualification(QualificationId.newId(), ACME, "Forklift").name())
        .isEqualTo("Forklift");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
  void GivenABlankName_WhenConstructingAQualification_ThenItIsRejected(String name) {
    assertThatThrownBy(() -> new Qualification(QualificationId.newId(), ACME, name))
        .isInstanceOf(InvalidQualificationNameException.class);
  }

  @Test
  void GivenANullName_WhenConstructingAQualification_ThenItIsRejected() {
    assertThatThrownBy(() -> new Qualification(QualificationId.newId(), ACME, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenANullIdentity_WhenConstructingAQualification_ThenItIsRejected() {
    assertThatThrownBy(() -> new Qualification(null, ACME, "Forklift"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenANullOrganisation_WhenConstructingAQualification_ThenItIsRejected() {
    assertThatThrownBy(() -> new Qualification(QualificationId.newId(), null, "Forklift"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenTwoQualificationsSharingAName_WhenComparingThem_ThenIdentityDecides() {
    assertThat(new Qualification(QualificationId.newId(), ACME, "Forklift"))
        .isNotEqualTo(new Qualification(QualificationId.newId(), ACME, "Forklift"));
  }

  @Test
  void GivenTwoQualificationsSharingAnIdentity_WhenComparingThem_ThenTheyAreTheSameThing() {
    QualificationId id = QualificationId.newId();
    Qualification original = new Qualification(id, ACME, "Forklift");
    Qualification renamed = new Qualification(id, ACME, "Forklift Licence");

    assertThat(original).isEqualTo(renamed).hasSameHashCodeAs(renamed);
  }
}
