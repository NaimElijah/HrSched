package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Specification of what the organisation store promises.
 *
 * <p>The only repository here whose reads are not scoped by tenant, because it is the register of
 * tenants. What it does promise is ordering — by name, then by identity for two sharing a name, so
 * the operator's listing is stable — and that saving under an existing identity replaces rather
 * than duplicates.
 */
class JdbcOrganisationRepositoryTest {

  /** Invented: an identity PostgreSQL sorts before {@link #LATER_IDENTITY}. */
  private static final String EARLIER_IDENTITY = "00000000-0000-4000-8000-000000000001";

  /** Invented: an identity PostgreSQL sorts after {@link #EARLIER_IDENTITY}. */
  private static final String LATER_IDENTITY = "ffffffff-ffff-4fff-bfff-ffffffffffff";

  private final JdbcOrganisationRepository organisations =
      new JdbcOrganisationRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
  }

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

  /** Two organisations sharing a name are still different customers, so the store must allow it. */
  @Test
  void GivenTwoOrganisationsWithOneName_WhenSaved_ThenBothAreKept() {
    organisations.save(new Organisation(OrganisationId.newId(), "Acme"));
    organisations.save(new Organisation(OrganisationId.newId(), "Acme"));

    assertThat(organisations.findAll()).hasSize(2);
  }

  /**
   * Ordering by name alone leaves two that share one in whatever order the database returns them,
   * which can change between two listings. Saved with the later identity first, so an order that
   * merely followed insertion would show it.
   */
  @Test
  void GivenTwoOrganisationsWithOneName_WhenListed_ThenTheyComeBackByIdentity() {
    OrganisationId later = new OrganisationId(UUID.fromString(LATER_IDENTITY));
    OrganisationId earlier = new OrganisationId(UUID.fromString(EARLIER_IDENTITY));
    organisations.save(new Organisation(later, "Acme"));
    organisations.save(new Organisation(earlier, "Acme"));

    assertThat(organisations.findAll())
        .extracting(Organisation::id)
        .containsExactly(earlier, later);
  }
}
