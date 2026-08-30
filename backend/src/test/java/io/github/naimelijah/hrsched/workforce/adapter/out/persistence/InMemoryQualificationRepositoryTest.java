package io.github.naimelijah.hrsched.workforce.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import org.junit.jupiter.api.Test;

/**
 * The tenant predicate and the ordering this adapter promises.
 *
 * <p>A qualification leaking across organisations would be worse than a cosmetic bug: a shift may
 * only demand a qualification its own organisation defined, so a leak here would let one tenant's
 * roster depend on another tenant's vocabulary.
 */
class InMemoryQualificationRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();

  private final InMemoryQualificationRepository repository = new InMemoryQualificationRepository();

  private static Qualification qualification(OrganisationId organisation, String name) {
    return new Qualification(QualificationId.newId(), organisation, name);
  }

  @Test
  void GivenAQualification_WhenReadBackByItsOwnOrganisation_ThenItIsFound() {
    Qualification forklift = qualification(ACME, "Forklift");
    repository.save(forklift);

    assertThat(repository.findById(ACME, forklift.id())).contains(forklift);
  }

  @Test
  void GivenAQualificationOfAnotherOrganisation_WhenReadBackByUs_ThenItIsNotFound() {
    Qualification theirs = qualification(RIVAL, "Their Qualification");
    repository.save(theirs);

    assertThat(repository.findById(ACME, theirs.id())).isEmpty();
  }

  @Test
  void GivenNoSuchQualification_WhenReadingIt_ThenNothingIsFound() {
    assertThat(repository.findById(ACME, QualificationId.newId())).isEmpty();
  }

  @Test
  void GivenTwoOrganisations_WhenListingOne_ThenOnlyItsOwnVocabularyComesBack() {
    repository.save(qualification(ACME, "Forklift"));
    repository.save(qualification(RIVAL, "Their Qualification"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Qualification::name)
        .containsExactly("Forklift");
  }

  @Test
  void GivenSeveralQualifications_WhenListingThem_ThenTheyComeBackInNameOrder() {
    repository.save(qualification(ACME, "Welding"));
    repository.save(qualification(ACME, "Forklift"));
    repository.save(qualification(ACME, "Nursing"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Qualification::name)
        .containsExactly("Forklift", "Nursing", "Welding");
  }

  @Test
  void GivenTheSameQualificationSavedTwice_WhenReadBack_ThenTheLaterVersionWins() {
    Qualification original = qualification(ACME, "Forklift");
    Qualification renamed = new Qualification(original.id(), ACME, "Forklift Licence");
    repository.save(original);
    repository.save(renamed);

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Qualification::name)
        .containsExactly("Forklift Licence");
  }

  @Test
  void GivenAnEmptyStore_WhenListing_ThenTheListIsEmptyRatherThanNull() {
    assertThat(repository.findByOrganisation(ACME)).isNotNull().isEmpty();
  }
}
