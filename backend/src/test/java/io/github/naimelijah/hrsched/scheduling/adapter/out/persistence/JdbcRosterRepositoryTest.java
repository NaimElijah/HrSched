package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tenant predicate this adapter promises, and the assignments a roster owns.
 *
 * <p>A roster is personal data about every colleague on it, so a read that forgot to filter would
 * hand one organisation another's staffing. Ordering is deliberately not asserted: unlike the shift
 * and employee adapters, this one promises none.
 *
 * <p>The status tests matter more here than they look. A roster is rebuilt through the only
 * transitions the domain offers, so a stored roster that could not be reached legally would fail to
 * load rather than quietly appear.
 */
class JdbcRosterRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final TimeRange THE_WEEK =
      new TimeRange(Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-14T00:00:00Z"));

  /** Invented to distinguish one stored version from another; not from any statute. */
  private static final int A_CONSTRAINT_VERSION = 3;

  private final JdbcRosterRepository repository =
      new JdbcRosterRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
    PostgresDatabase.given(ACME, RIVAL);
  }

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
    repository.save(draft.publishedUnder(A_CONSTRAINT_VERSION));

    assertThat(repository.findById(ACME, draft.id()))
        .get()
        .extracting(Roster::status)
        .isEqualTo(RosterStatus.PUBLISHED);
  }

  @Test
  void GivenAnEmptyStore_WhenListing_ThenTheListIsEmptyRatherThanNull() {
    assertThat(repository.findByOrganisation(ACME)).isNotNull().isEmpty();
  }

  @Test
  void GivenADraft_WhenReadBack_ThenItHasNoApprovingConstraintVersion() {
    Roster draft = draft(ACME);
    repository.save(draft);

    assertThat(repository.findById(ACME, draft.id()))
        .get()
        .extracting(Roster::validatedAgainstConstraintVersion)
        .isNull();
  }

  @Test
  void GivenAPublishedRoster_WhenReadBack_ThenItKeepsTheVersionThatApprovedIt() {
    Roster published = draft(ACME).publishedUnder(A_CONSTRAINT_VERSION);
    repository.save(published);

    assertThat(repository.findById(ACME, published.id()))
        .get()
        .extracting(Roster::validatedAgainstConstraintVersion)
        .isEqualTo(A_CONSTRAINT_VERSION);
  }

  @Test
  void GivenASupersededRoster_WhenReadBack_ThenItIsStillSuperseded() {
    Roster superseded = draft(ACME).publishedUnder(A_CONSTRAINT_VERSION).superseded();
    repository.save(superseded);

    assertThat(repository.findById(ACME, superseded.id()))
        .get()
        .extracting(Roster::status)
        .isEqualTo(RosterStatus.SUPERSEDED);
  }

  @Test
  void GivenARosterWithAssignments_WhenReadBack_ThenEveryAssignmentComesBack() {
    Assignment ada = new Assignment(ShiftId.newId(), EmployeeId.newId());
    Assignment grace = new Assignment(ShiftId.newId(), EmployeeId.newId());
    Roster staffed = draft(ACME).with(ada).with(grace);
    repository.save(staffed);

    assertThat(repository.findById(ACME, staffed.id()))
        .get()
        .extracting(Roster::assignments)
        .satisfies(assignments -> assertThat(assignments).containsExactlyInAnyOrder(ada, grace));
  }

  /**
   * Publishing supersedes and re-saves rosters, so a save has to leave the assignment table
   * agreeing with the aggregate rather than accumulating what was there before.
   */
  @Test
  void GivenARosterWhoseAssignmentsChange_WhenSavedAgain_ThenTheOldOnesAreGone() {
    Assignment ada = new Assignment(ShiftId.newId(), EmployeeId.newId());
    Assignment grace = new Assignment(ShiftId.newId(), EmployeeId.newId());
    Roster staffed = draft(ACME).with(ada);
    repository.save(staffed);

    repository.save(staffed.withAssignments(List.of(grace)));

    assertThat(repository.findById(ACME, staffed.id()))
        .get()
        .extracting(Roster::assignments)
        .isEqualTo(List.of(grace));
  }

  @Test
  void GivenTwoRostersWithAssignments_WhenListed_ThenNeitherGetsTheOthers() {
    Assignment ada = new Assignment(ShiftId.newId(), EmployeeId.newId());
    Assignment grace = new Assignment(ShiftId.newId(), EmployeeId.newId());
    Roster first = draft(ACME).with(ada);
    Roster second = draft(ACME).with(grace);
    repository.save(first);
    repository.save(second);

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Roster::assignments)
        .containsExactlyInAnyOrder(List.of(ada), List.of(grace));
  }
}
