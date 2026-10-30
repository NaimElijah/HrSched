package io.github.naimelijah.hrsched.workforce.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import java.util.UUID;
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

  /**
   * Two qualifications may share a name until R23 is built, and ordering by name alone leaves
   * theirs to the plan the database picks. Their identity settles it. Saved in the opposite order,
   * so insertion order cannot pass for it.
   */
  @Test
  void GivenTwoQualificationsSharingAName_WhenListingThem_ThenTheirIdentityOrdersThem() {
    // Invented identities, chosen only for which sorts first.
    QualificationId first =
        new QualificationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    QualificationId second =
        new QualificationId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    repository.save(new Qualification(second, ACME, "Forklift"));
    repository.save(new Qualification(first, ACME, "Forklift"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Qualification::id)
        .containsExactly(first, second);
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
   * Pins a known gap, not a rule. R23 makes a qualification's name unique within its organisation,
   * and until #95 builds that, neither the domain nor the store refuses a repeated name. When #95
   * lands, this test is revisited.
   */
  @Test
  void GivenTwoQualificationsSharingAName_WhenSaved_ThenBothAreKeptUntilR23IsBuilt() {
    repository.save(qualification(ACME, "Forklift"));
    repository.save(qualification(ACME, "Forklift"));

    assertThat(repository.findByOrganisation(ACME)).hasSize(2);
  }

  /**
   * {@code save} is the one repository method carrying no {@code OrganisationId}, so the upsert is
   * the only thing standing between a re-used identity and another tenant's row. A cross-tenant
   * write is a security incident rather than a bug.
   */
  @Test
  void GivenAnIdentityHeldByAnotherOrganisation_WhenSavingOverIt_ThenItIsRefused() {
    Qualification theirs = qualification(RIVAL, "Their Qualification");
    repository.save(theirs);

    assertThatThrownBy(() -> repository.save(new Qualification(theirs.id(), ACME, "Forklift")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another organisation");
  }

  @Test
  void GivenAnIdentityHeldByAnotherOrganisation_WhenSavingOverIt_ThenTheirRowIsUntouched() {
    Qualification theirs = qualification(RIVAL, "Their Qualification");
    repository.save(theirs);

    assertThatThrownBy(() -> repository.save(new Qualification(theirs.id(), ACME, "Forklift")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(repository.findById(RIVAL, theirs.id()))
        .get()
        .extracting(Qualification::name)
        .isEqualTo("Their Qualification");
  }
}
