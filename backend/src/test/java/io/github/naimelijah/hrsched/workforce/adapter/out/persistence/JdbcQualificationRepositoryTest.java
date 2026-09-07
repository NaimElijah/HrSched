package io.github.naimelijah.hrsched.workforce.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tenant predicate and the ordering this adapter promises.
 *
 * <p>A qualification leaking across organisations would be worse than a cosmetic bug: a shift may
 * only demand a qualification its own organisation defined, so a leak here would let one tenant's
 * roster depend on another tenant's vocabulary.
 */
class JdbcQualificationRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();

  private final JdbcQualificationRepository repository =
      new JdbcQualificationRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
    PostgresDatabase.given(ACME, RIVAL);
  }

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
    Qualification ours = qualification(ACME, "Forklift");
    repository.save(ours);
    repository.save(qualification(RIVAL, "Their Qualification"));

    assertThat(repository.findByOrganisation(ACME)).containsExactly(ours);
  }

  @Test
  void GivenSeveralQualifications_WhenListingThem_ThenTheyComeBackInNameOrder() {
    repository.save(qualification(ACME, "Forklift"));
    repository.save(qualification(ACME, "Crane"));
    repository.save(qualification(ACME, "Rigging"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Qualification::name)
        .containsExactly("Crane", "Forklift", "Rigging");
  }

  @Test
  void GivenTheSameQualificationSavedTwice_WhenReadBack_ThenTheLaterVersionWins() {
    Qualification original = qualification(ACME, "Forklift");
    repository.save(original);
    repository.save(new Qualification(original.id(), ACME, "Forklift (counterbalance)"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Qualification::name)
        .containsExactly("Forklift (counterbalance)");
  }

  @Test
  void GivenAnEmptyStore_WhenListing_ThenTheListIsEmptyRatherThanNull() {
    assertThat(repository.findByOrganisation(ACME)).isNotNull().isEmpty();
  }

  /**
   * Nothing in the domain refuses a repeated name, so nothing in the store may either — a unique
   * index here would be a new domain rule introduced as a storage detail.
   */
  @Test
  void GivenTwoQualificationsSharingAName_WhenSaved_ThenBothAreKept() {
    repository.save(qualification(ACME, "Forklift"));
    repository.save(qualification(ACME, "Forklift"));

    assertThat(repository.findByOrganisation(ACME)).hasSize(2);
  }
}
