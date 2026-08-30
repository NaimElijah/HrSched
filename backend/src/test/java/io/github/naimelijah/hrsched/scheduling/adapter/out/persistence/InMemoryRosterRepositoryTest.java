package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The tenant predicate this adapter promises.
 *
 * <p>A roster is personal data about every colleague on it, so a read that forgot to filter would
 * hand one organisation another's staffing. Ordering is deliberately not asserted: unlike the shift
 * and employee adapters, this one promises none.
 */
class InMemoryRosterRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final TimeRange THE_WEEK =
      new TimeRange(Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-14T00:00:00Z"));

  private final InMemoryRosterRepository repository = new InMemoryRosterRepository();

  private static Roster draft(OrganisationId organisation) {
    return new Roster(RosterId.newId(), organisation, THE_WEEK);
  }

  @Test
  void GivenARoster_WhenReadBackByItsOwnOrganisation_ThenItIsFound() {
    Roster ours = draft(ACME);
    repository.save(ours);

    assertThat(repository.findById(ACME, ours.id())).contains(ours);
  }

  @Test
  void GivenARosterOfAnotherOrganisation_WhenReadBackByUs_ThenItIsNotFound() {
    Roster theirs = draft(RIVAL);
    repository.save(theirs);

    assertThat(repository.findById(ACME, theirs.id())).isEmpty();
  }

  @Test
  void GivenNoSuchRoster_WhenReadingIt_ThenNothingIsFound() {
    assertThat(repository.findById(ACME, RosterId.newId())).isEmpty();
  }

  @Test
  void GivenTwoOrganisations_WhenListingOne_ThenOnlyItsOwnRostersComeBack() {
    Roster ours = draft(ACME);
    repository.save(ours);
    repository.save(draft(RIVAL));

    assertThat(repository.findByOrganisation(ACME)).containsExactly(ours);
  }

  @Test
  void GivenAPublishedRosterSavedOverItsDraft_WhenReadBack_ThenTheLaterVersionWins() {
    Roster draft = draft(ACME);
    repository.save(draft);
    repository.save(draft.publishedUnder(1));

    assertThat(repository.findById(ACME, draft.id()))
        .get()
        .extracting(Roster::status)
        .isEqualTo(RosterStatus.PUBLISHED);
  }

  @Test
  void GivenAnEmptyStore_WhenListing_ThenTheListIsEmptyRatherThanNull() {
    assertThat(repository.findByOrganisation(ACME)).isNotNull().isEmpty();
  }
}
