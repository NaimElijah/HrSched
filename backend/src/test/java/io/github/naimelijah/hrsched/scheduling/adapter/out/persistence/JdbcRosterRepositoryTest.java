package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterPublicationConflictException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

  /** Shares Thursday to Sunday with {@link #THE_WEEK}. */
  private static final TimeRange OVERLAPPING_WEEK =
      new TimeRange(Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"));

  /** Starts the instant {@link #THE_WEEK} ends, so the two touch without overlapping. */
  private static final TimeRange THE_NEXT_WEEK =
      new TimeRange(Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-21T00:00:00Z"));

  /** Invented to distinguish one stored version from another; not from any statute. */
  private static final int A_CONSTRAINT_VERSION = 3;

  /**
   * How long a racing writer may take before the test gives up on it. Far longer than a publication
   * needs, including PostgreSQL's one-second wait before it resolves a deadlock, so reaching it
   * means something is stuck rather than slow.
   */
  private static final long STUCK_AFTER_SECONDS = 30;

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

  private static Roster published(OrganisationId organisation, TimeRange period) {
    return new Roster(RosterId.newId(), organisation, period).publishedUnder(A_CONSTRAINT_VERSION);
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

  /**
   * R11 in the schema. Publishing supersedes before it publishes, so a writer only reaches this
   * without having seen the other roster - which is why it is a conflict to reload and retry, not a
   * bad request.
   */
  @Test
  void GivenAPublishedRoster_WhenAnOverlappingOneIsStoredAsPublished_ThenItIsRefusedAsAConflict() {
    repository.save(published(ACME, THE_WEEK));
    Roster overlapping = published(ACME, OVERLAPPING_WEEK);

    assertThatThrownBy(() -> repository.save(overlapping))
        .isInstanceOf(RosterPublicationConflictException.class);
  }

  @Test
  void GivenPublishedRostersThatOnlyTouch_WhenBothAreStored_ThenBothArePublished() {
    repository.save(published(ACME, THE_WEEK));
    repository.save(published(ACME, THE_NEXT_WEEK));

    assertThat(repository.findByOrganisation(ACME))
        .describedAs("a period's end is exclusive, so the next week starting then is no overlap")
        .extracting(Roster::status)
        .containsOnly(RosterStatus.PUBLISHED)
        .hasSize(2);
  }

  @Test
  void
      GivenAnOverlappingPublishedRosterOfAnotherOrganisation_WhenPublishing_ThenItIsNotAConflict() {
    repository.save(published(RIVAL, THE_WEEK));
    Roster ours = published(ACME, OVERLAPPING_WEEK);

    repository.save(ours);

    assertThat(repository.findById(ACME, ours.id()))
        .get()
        .extracting(Roster::status)
        .isEqualTo(RosterStatus.PUBLISHED);
  }

  /** History may overlap: a superseded roster governs nothing, so it blocks nothing. */
  @Test
  void GivenASupersededRoster_WhenAnOverlappingOneIsPublished_ThenItIsStored() {
    repository.save(published(ACME, THE_WEEK).superseded());
    Roster replacement = published(ACME, OVERLAPPING_WEEK);

    repository.save(replacement);

    assertThat(repository.findById(ACME, replacement.id()))
        .get()
        .extracting(Roster::status)
        .isEqualTo(RosterStatus.PUBLISHED);
  }

  /** Generating a draft over a published week is how every replacement starts. */
  @Test
  void GivenAPublishedRoster_WhenAnOverlappingDraftIsStored_ThenItIsAccepted() {
    repository.save(published(ACME, THE_WEEK));
    Roster draft = new Roster(RosterId.newId(), ACME, OVERLAPPING_WEEK);

    repository.save(draft);

    assertThat(repository.findById(ACME, draft.id())).contains(draft);
  }

  /**
   * The race the constraint exists for: two writers that each publish without seeing the other,
   * because neither has committed. Publication takes a lock so that this cannot happen through the
   * use cases (ADR-027), so this deliberately takes none - it is what stands behind any writer that
   * forgets to.
   *
   * <p>Which writer is refused, and whether PostgreSQL reports it as an exclusion violation or
   * picks one as a deadlock victim, depends on timing. That exactly one is refused does not.
   */
  @Test
  @Timeout(STUCK_AFTER_SECONDS * 2)
  void GivenTwoTransactionsOpenAtOnce_WhenBothPublishOverlappingRosters_ThenExactlyOneIsRefused()
      throws Exception {
    Roster first = draft(ACME);
    Roster second = new Roster(RosterId.newId(), ACME, OVERLAPPING_WEEK);
    repository.save(first);
    repository.save(second);
    TransactionTemplate transaction = new TransactionTemplate(PostgresDatabase.transactions());
    CyclicBarrier bothOpen = new CyclicBarrier(2);
    ExecutorService writers = Executors.newFixedThreadPool(2);

    List<Throwable> refusals = new ArrayList<>();
    try {
      List<Future<?>> publications =
          List.of(
              writers.submit(() -> publishOnceBothAreOpen(transaction, bothOpen, first)),
              writers.submit(() -> publishOnceBothAreOpen(transaction, bothOpen, second)));
      for (Future<?> publication : publications) {
        try {
          publication.get(STUCK_AFTER_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException refused) {
          refusals.add(refused.getCause());
        }
      }
    } finally {
      writers.shutdownNow();
    }

    assertThat(refusals).singleElement().isInstanceOf(RosterPublicationConflictException.class);
    assertThat(repository.findByOrganisation(ACME))
        .filteredOn(roster -> roster.status() == RosterStatus.PUBLISHED)
        .hasSize(1);
  }

  /**
   * Reads, waits until the other writer has read too, then publishes. The read is what starts the
   * transaction in PostgreSQL, so after the barrier both are open and neither has seen the other.
   */
  private void publishOnceBothAreOpen(
      TransactionTemplate transaction, CyclicBarrier bothOpen, Roster draft) {
    transaction.executeWithoutResult(
        status -> {
          repository.findByOrganisation(ACME);
          try {
            bothOpen.await(STUCK_AFTER_SECONDS, TimeUnit.SECONDS);
          } catch (InterruptedException | BrokenBarrierException | TimeoutException stuck) {
            throw new IllegalStateException("The other writer never opened its transaction", stuck);
          }
          repository.save(draft.publishedUnder(A_CONSTRAINT_VERSION));
        });
  }

  /**
   * The lock ADR-027 relies on, against the real database: while one transaction holds an
   * organisation's publications, another transaction asking for them waits, and one asking for a
   * different organisation's does not.
   *
   * <p>The waiting is shown by giving the second transaction a lock timeout, so a wait becomes a
   * refusal the test can see rather than a pause it would have to time. Single-threaded: the second
   * transaction runs on its own connection while the first is suspended, still holding the lock.
   */
  @Test
  @Timeout(STUCK_AFTER_SECONDS)
  void GivenOneTransactionHoldsAnOrganisationsPublications_WhenAnotherAsksForThem_ThenItWaits() {
    TransactionTemplate holder = new TransactionTemplate(PostgresDatabase.transactions());
    TransactionTemplate another = new TransactionTemplate(PostgresDatabase.transactions());
    another.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    holder.executeWithoutResult(
        held -> {
          repository.lockPublicationsOf(ACME);

          assertThatThrownBy(() -> another.executeWithoutResult(probe -> askBrieflyFor(ACME)))
              .rootCause()
              .isInstanceOfSatisfying(
                  SQLException.class,
                  cause -> assertThat(cause.getSQLState()).isEqualTo(LOCK_NOT_AVAILABLE));
          assertThatCode(() -> another.executeWithoutResult(probe -> askBrieflyFor(RIVAL)))
              .describedAs("another organisation's publications do not wait on this one's")
              .doesNotThrowAnyException();
        });

    assertThatCode(() -> another.executeWithoutResult(probe -> askBrieflyFor(ACME)))
        .describedAs("the lock ends with the transaction that took it")
        .doesNotThrowAnyException();
  }

  /**
   * How long a probing transaction waits for the lock before giving up. Any wait at all proves the
   * point; this only has to be long enough that a lock free to take is never mistaken for one held.
   */
  private static final String A_PROBE_WAIT = "200ms";

  /**
   * PostgreSQL's {@code lock_not_available}: the statement waited for a lock until {@code
   * lock_timeout} ran out. Asserted by SQLState because Spring reports it as an uncategorised
   * exception rather than as a lock failure, so the type would say nothing about the wait.
   */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  /**
   * Asks for an organisation's publications, giving up after a short wait instead of waiting on.
   */
  private void askBrieflyFor(OrganisationId organisation) {
    PostgresDatabase.jdbcClient()
        .sql("SELECT set_config('lock_timeout', :wait, true)")
        .param("wait", A_PROBE_WAIT)
        .query(String.class)
        .single();
    repository.lockPublicationsOf(organisation);
  }
}
