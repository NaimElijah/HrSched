package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The tenant predicate and the ordering this adapter promises.
 *
 * <p>The organisation filter is the reason these tests exist. It is applied after the map lookup,
 * so a shift is reachable by its identifier from anywhere in the process; only the filter stops one
 * organisation reading another's. A real store will push that predicate into the query, and these
 * tests are what the replacement has to keep satisfying.
 */
class InMemoryShiftRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();

  private final InMemoryShiftRepository repository = new InMemoryShiftRepository();

  private static TimeRange between(String start, String end) {
    return new TimeRange(Instant.parse(start), Instant.parse(end));
  }

  private static Shift shift(OrganisationId organisation, String start, String end) {
    return new Shift(ShiftId.newId(), organisation, between(start, end), 1, Set.of());
  }

  @Test
  void GivenAShift_WhenFindingItInItsOwnOrganisation_ThenItIsFound() {
    Shift morning = shift(ACME, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(morning);

    assertThat(repository.findById(ACME, morning.id())).contains(morning);
  }

  @Test
  void GivenAShiftOfAnotherOrganisation_WhenFindingItById_ThenItIsNotFound() {
    Shift theirs = shift(RIVAL, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(theirs);

    assertThat(repository.findById(ACME, theirs.id())).isEmpty();
  }

  @Test
  void GivenNoSuchShift_WhenFindingItById_ThenItIsNotFound() {
    assertThat(repository.findById(ACME, ShiftId.newId())).isEmpty();
  }

  @Test
  void GivenShiftsInTwoOrganisations_WhenFindingWithinASpan_ThenOnlyOurOwnAreReturned() {
    Shift ours = shift(ACME, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(ours);
    repository.save(shift(RIVAL, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z"));

    assertThat(repository.findWithin(ACME, between("2026-09-07T00:00:00Z", "2026-09-08T00:00:00Z")))
        .containsExactly(ours);
  }

  @Test
  void GivenAShiftOutsideTheSpan_WhenFindingWithin_ThenItIsNotReturned() {
    repository.save(shift(ACME, "2026-09-10T08:00:00Z", "2026-09-10T16:00:00Z"));

    assertThat(repository.findWithin(ACME, between("2026-09-07T00:00:00Z", "2026-09-08T00:00:00Z")))
        .isEmpty();
  }

  @Test
  void GivenAShiftStraddlingTheSpanEdge_WhenFindingWithin_ThenItIsReturned() {
    Shift overnight = shift(ACME, "2026-09-07T22:00:00Z", "2026-09-08T06:00:00Z");
    repository.save(overnight);

    assertThat(repository.findWithin(ACME, between("2026-09-07T00:00:00Z", "2026-09-08T00:00:00Z")))
        .containsExactly(overnight);
  }

  @Test
  void GivenAShiftEndingExactlyWhenTheSpanBegins_WhenFindingWithin_ThenItIsNotReturned() {
    repository.save(shift(ACME, "2026-09-06T16:00:00Z", "2026-09-07T00:00:00Z"));

    assertThat(repository.findWithin(ACME, between("2026-09-07T00:00:00Z", "2026-09-08T00:00:00Z")))
        .isEmpty();
  }

  @Test
  void GivenSeveralShifts_WhenFindingWithin_ThenTheyComeBackInStartOrder() {
    Shift evening = shift(ACME, "2026-09-07T16:00:00Z", "2026-09-07T23:00:00Z");
    Shift morning = shift(ACME, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(evening);
    repository.save(morning);

    assertThat(repository.findWithin(ACME, between("2026-09-07T00:00:00Z", "2026-09-08T00:00:00Z")))
        .containsExactly(morning, evening);
  }

  @Test
  void GivenTheSameShiftSavedTwice_WhenFindingIt_ThenOnlyTheLatestIsHeld() {
    Shift original = shift(ACME, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    Shift revised =
        new Shift(
            original.id(),
            ACME,
            between("2026-09-07T09:00:00Z", "2026-09-07T17:00:00Z"),
            2,
            Set.of());
    repository.save(original);
    repository.save(revised);

    assertThat(repository.findWithin(ACME, between("2026-09-07T00:00:00Z", "2026-09-08T00:00:00Z")))
        .containsExactly(revised);
  }

  @Test
  void GivenNothingSaved_WhenFindingWithinASpan_ThenNothingIsReturned() {
    assertThat(repository.findWithin(ACME, between("2026-09-07T00:00:00Z", "2026-09-08T00:00:00Z")))
        .isEmpty();
  }
}
