package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.STUCK_AFTER_SECONDS;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.awaitWaitingOnALock;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.awaitWaitingOnALockOr;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.backendServingThisTransaction;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.refusalsOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterPublicationConflictException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.Writer;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  /** When a roster in these tests was published. Any instant would do. */
  private static final Instant PUBLISHED_AT = Instant.parse("2026-09-01T09:00:00Z");

  /** When a newer roster superseded it, and so when that newer one was published. */
  private static final Instant SUPERSEDED_AT = Instant.parse("2026-09-03T15:30:00Z");

  /** A Thursday afternoon, inside both {@link #THE_WEEK} and {@link #OVERLAPPING_WEEK}. */
  private static final Instant THURSDAY_AFTERNOON = Instant.parse("2026-09-10T15:00:00Z");

  /** PostgreSQL's {@code deadlock_detected}: the SQLState a deadlock victim is aborted with. */
  private static final String DEADLOCK_DETECTED = "40P01";

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
    return new Roster(RosterId.newId(), organisation, period)
        .publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT);
  }

  /**
   * R37's reconstruction, stated once (ADR-037): the roster whose period holds the scheduled
   * instant, and which had been published but not yet superseded at the moment of record.
   */
  private static Optional<RosterId> authoritativeAt(
      List<Roster> history, Instant recordedAt, Instant scheduled) {
    return history.stream()
        .filter(roster -> roster.publishedAt() != null)
        .filter(roster -> !roster.publishedAt().isAfter(recordedAt))
        .filter(
            roster -> roster.supersededAt() == null || recordedAt.isBefore(roster.supersededAt()))
        .filter(roster -> !roster.period().start().isAfter(scheduled))
        .filter(roster -> scheduled.isBefore(roster.period().end()))
        .map(Roster::id)
        .reduce(
            (one, other) -> {
              throw new AssertionError("%s and %s governed one moment".formatted(one, other));
            });
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
    repository.save(draft.publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT));

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
    Roster published = draft(ACME).publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT);
    repository.save(published);

    assertThat(repository.findById(ACME, published.id()))
        .get()
        .extracting(Roster::validatedAgainstConstraintVersion)
        .isEqualTo(A_CONSTRAINT_VERSION);
  }

  @Test
  void GivenASupersededRoster_WhenReadBack_ThenItIsStillSuperseded() {
    Roster published = published(ACME, THE_WEEK);
    repository.save(published);
    repository.save(published.superseded(SUPERSEDED_AT));

    assertThat(repository.findById(ACME, published.id()))
        .get()
        .extracting(Roster::status)
        .isEqualTo(RosterStatus.SUPERSEDED);
  }

  @Test
  void GivenASupersededRoster_WhenReadBack_ThenBothItsMomentsOfRecordComeBack() {
    Roster published = published(ACME, THE_WEEK);
    repository.save(published);
    repository.save(published.superseded(SUPERSEDED_AT));

    Roster stored = repository.findById(ACME, published.id()).orElseThrow();

    assertThat(stored.publishedAt()).isEqualTo(PUBLISHED_AT);
    assertThat(stored.supersededAt()).isEqualTo(SUPERSEDED_AT);
  }

  /**
   * What R37 asks for. After a roster is superseded, which roster governed a moment of the schedule
   * can still be answered for any moment of record: the old one before the supersession, the new
   * one from then on, and neither before anything was published.
   */
  @Test
  void
      GivenARosterSupersededLater_WhenReconstructingAPastMoment_ThenTheRosterGoverningThenIsFound() {
    Roster first = published(ACME, THE_WEEK);
    repository.save(first);
    Roster replacement = new Roster(RosterId.newId(), ACME, OVERLAPPING_WEEK);
    repository.save(replacement);
    repository.save(first.superseded(SUPERSEDED_AT));
    repository.save(replacement.publishedUnder(A_CONSTRAINT_VERSION, SUPERSEDED_AT));

    List<Roster> history = repository.findByOrganisation(ACME);

    assertThat(authoritativeAt(history, PUBLISHED_AT.minusSeconds(1), THURSDAY_AFTERNOON))
        .isEmpty();
    assertThat(authoritativeAt(history, SUPERSEDED_AT.minusNanos(1_000), THURSDAY_AFTERNOON))
        .contains(first.id());
    assertThat(authoritativeAt(history, SUPERSEDED_AT, THURSDAY_AFTERNOON))
        .contains(replacement.id());
  }

  /** A publication is recorded once. Recording it again would rewrite when it happened. */
  @Test
  void GivenAPublishedRoster_WhenStoredAgain_ThenItIsRefusedAsNoLongerEditable() {
    Roster published = published(ACME, THE_WEEK);
    repository.save(published);

    assertThatThrownBy(() -> repository.save(published))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenAPublishedRoster_WhenItsDraftIsStoredOverIt_ThenItIsRefusedAndKeepsItsAssignments() {
    Assignment ada = new Assignment(ShiftId.newId(), EmployeeId.newId());
    Roster draft = draft(ACME).with(ada);
    repository.save(draft);
    repository.save(draft.publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT));

    assertThatThrownBy(() -> repository.save(draft.withAssignments(List.of())))
        .describedAs("a draft stored over its own publication would un-publish it")
        .isInstanceOf(RosterNotEditableException.class);

    Roster stored = repository.findById(ACME, draft.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(stored.assignments()).containsExactly(ada);
  }

  @Test
  void GivenADraft_WhenStoredAsSuperseded_ThenItIsRefusedAsNeverPublished() {
    Roster draft = draft(ACME);
    repository.save(draft);
    Roster superseded =
        draft.publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT).superseded(SUPERSEDED_AT);

    assertThatThrownBy(() -> repository.save(superseded))
        .isInstanceOf(RosterNotEditableException.class);
    assertThat(repository.findById(ACME, draft.id()))
        .get()
        .extracting(Roster::status)
        .isEqualTo(RosterStatus.DRAFT);
  }

  /**
   * Superseded once, at one moment. A second supersession would rewrite when it stopped governing.
   */
  @Test
  void GivenASupersededRoster_WhenSupersededAgain_ThenItIsRefusedAndKeepsItsMoment() {
    Roster published = published(ACME, THE_WEEK);
    repository.save(published);
    repository.save(published.superseded(SUPERSEDED_AT));

    assertThatThrownBy(() -> repository.save(published.superseded(SUPERSEDED_AT.plusSeconds(60))))
        .isInstanceOf(RosterNotEditableException.class);
    assertThat(repository.findById(ACME, published.id()))
        .get()
        .extracting(Roster::supersededAt)
        .isEqualTo(SUPERSEDED_AT);
  }

  @Test
  void GivenARosterOfAnotherOrganisation_WhenStoredUnderTheSameIdentityAsOurs_ThenItIsRefused() {
    Roster theirs = draft(RIVAL);
    repository.save(theirs);
    Roster ours = new Roster(theirs.id(), ACME, THE_NEXT_WEEK);

    assertThatThrownBy(() -> repository.save(ours)).isInstanceOf(IllegalStateException.class);
    // Roster compares by identity, so only a field the overwrite would change can show it did not.
    assertThat(repository.findById(RIVAL, theirs.id()))
        .get()
        .extracting(Roster::period)
        .isEqualTo(THE_WEEK);
  }

  @Test
  void
      GivenARosterOfAnotherOrganisation_WhenSupersededUnderTheSameIdentityAsOurs_ThenItIsRefused() {
    Roster theirs = published(RIVAL, THE_WEEK);
    repository.save(theirs);
    Roster ours =
        new Roster(theirs.id(), ACME, THE_WEEK)
            .publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT)
            .superseded(SUPERSEDED_AT);

    assertThatThrownBy(() -> repository.save(ours)).isInstanceOf(IllegalStateException.class);
    assertThat(repository.findById(RIVAL, theirs.id()))
        .get()
        .extracting(Roster::status)
        .isEqualTo(RosterStatus.PUBLISHED);
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
   * A draft is stored again every time it is edited, so a save has to leave the assignment table
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

  /**
   * A superseded roster governed only until it was superseded, so a replacement governing from that
   * moment on overlaps it in the schedule but at no moment of record.
   */
  @Test
  void GivenASupersededRoster_WhenAnOverlappingOneIsPublishedAsItWasSuperseded_ThenItIsStored() {
    Roster first = published(ACME, THE_WEEK);
    repository.save(first);
    repository.save(first.superseded(SUPERSEDED_AT));
    Roster replacement =
        new Roster(RosterId.newId(), ACME, OVERLAPPING_WEEK)
            .publishedUnder(A_CONSTRAINT_VERSION, SUPERSEDED_AT);

    repository.save(replacement);

    assertThat(repository.findById(ACME, replacement.id()))
        .get()
        .extracting(Roster::status)
        .isEqualTo(RosterStatus.PUBLISHED);
  }

  /**
   * R11 held for the past as well as the present. A roster recorded as published while the old one
   * still governed would make two rosters authoritative over the same days at once, and the history
   * could no longer say which; the schema refuses it however it is arrived at.
   */
  @Test
  void GivenASupersededRoster_WhenOneIsRecordedAsPublishedWhileItStillGoverned_ThenItIsAConflict() {
    Roster first = published(ACME, THE_WEEK);
    repository.save(first);
    repository.save(first.superseded(SUPERSEDED_AT));
    Roster backdated =
        new Roster(RosterId.newId(), ACME, OVERLAPPING_WEEK)
            .publishedUnder(A_CONSTRAINT_VERSION, SUPERSEDED_AT.minusSeconds(3600));

    assertThatThrownBy(() -> repository.save(backdated))
        .isInstanceOf(RosterPublicationConflictException.class);
  }

  /**
   * The race a status stored in place lost silently: an edit of a draft that commits after the
   * draft was published used to write it back to a draft, un-publishing it with nothing recorded.
   * The edit waits for the publication's row, then finds it published and is refused.
   *
   * <p>The publication commits only once the edit is waiting on its row. Otherwise the commit could
   * land before the edit arrived, and this would be the same as storing over a published roster
   * with nobody racing - which a guard read before the write, rather than in it, would also pass.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenAnEditWaitingOnAPublication_WhenThePublicationCommits_ThenTheEditIsRefused()
      throws Exception {
    Assignment ada = new Assignment(ShiftId.newId(), EmployeeId.newId());
    Roster draft = draft(ACME).with(ada);
    repository.save(draft);

    AtomicInteger editor = new AtomicInteger();
    List<Throwable> refusals =
        refusalsOf(
            waitForTheOther -> {
              repository.save(draft.publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT));
              waitForTheOther.run();
              awaitWaitingOnALock(editor.get());
            },
            waitForTheOther -> {
              editor.set(backendServingThisTransaction());
              waitForTheOther.run();
              repository.save(draft.withAssignments(List.of()));
            });

    assertThat(refusals).singleElement().isInstanceOf(RosterNotEditableException.class);
    Roster stored = repository.findById(ACME, draft.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(stored.assignments()).containsExactly(ada);
  }

  /**
   * #33's lost update. Each edit reads the draft, adds to it and stores it whole, so without a lock
   * the later save deletes the assignment the earlier one added. Read for update, the second edit
   * waits for the first to commit and then reads what it stored, and both assignments survive.
   *
   * <p>The first edit stores only once the second is waiting on its row, so the test cannot pass by
   * the second simply arriving late. If the second does not wait at all, the first goes on as soon
   * as the second has read, and the lost assignment is what fails the test.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenAnEditHoldingADraft_WhenAnotherEditReadsItForUpdate_ThenItWaitsAndBothEditsAreKept()
      throws Exception {
    Roster draft = draft(ACME);
    repository.save(draft);
    Assignment ada = new Assignment(ShiftId.newId(), EmployeeId.newId());
    Assignment grace = new Assignment(ShiftId.newId(), EmployeeId.newId());

    AtomicInteger second = new AtomicInteger();
    AtomicBoolean secondHasRead = new AtomicBoolean();
    List<Throwable> refusals =
        refusalsOf(
            waitForTheOther -> {
              Roster held = repository.findForUpdate(ACME, draft.id()).orElseThrow();
              waitForTheOther.run();
              awaitWaitingOnALockOr(second.get(), secondHasRead::get);
              repository.save(held.with(ada));
            },
            waitForTheOther -> {
              second.set(backendServingThisTransaction());
              waitForTheOther.run();
              Roster read = repository.findForUpdate(ACME, draft.id()).orElseThrow();
              secondHasRead.set(true);
              repository.save(read.with(grace));
            });

    assertThat(refusals).isEmpty();
    assertThat(repository.findById(ACME, draft.id()).orElseThrow().assignments())
        .containsExactlyInAnyOrder(ada, grace);
  }

  /**
   * The other half of #33: an edit committing between a publication's read and its save. Read for
   * update, the publication waits for the edit, and publishes the roster carrying it rather than
   * storing the copy it read over it.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void
      GivenAnEditHoldingADraft_WhenItsPublicationReadsItForUpdate_ThenThePublishedRosterCarriesTheEdit()
          throws Exception {
    Roster draft = draft(ACME);
    repository.save(draft);
    Assignment ada = new Assignment(ShiftId.newId(), EmployeeId.newId());

    AtomicInteger publication = new AtomicInteger();
    AtomicBoolean publicationHasRead = new AtomicBoolean();
    List<Throwable> refusals =
        refusalsOf(
            waitForTheOther -> {
              Roster held = repository.findForUpdate(ACME, draft.id()).orElseThrow();
              waitForTheOther.run();
              awaitWaitingOnALockOr(publication.get(), publicationHasRead::get);
              repository.save(held.with(ada));
            },
            waitForTheOther -> {
              publication.set(backendServingThisTransaction());
              waitForTheOther.run();
              Roster read = repository.findForUpdate(ACME, draft.id()).orElseThrow();
              publicationHasRead.set(true);
              repository.save(read.publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT));
            });

    assertThat(refusals).isEmpty();
    Roster stored = repository.findById(ACME, draft.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(stored.assignments()).containsExactly(ada);
  }

  /**
   * The same wait the other way round: an edit reaching a draft a publication holds waits for it,
   * then reads the roster as published, so the use case refuses it as no longer a draft before it
   * changes anything.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenAPublicationHoldingADraft_WhenAnEditReadsItForUpdate_ThenItFindsItPublished()
      throws Exception {
    Roster draft = draft(ACME);
    repository.save(draft);

    AtomicInteger edit = new AtomicInteger();
    AtomicReference<RosterStatus> seenByTheEdit = new AtomicReference<>();
    List<Throwable> refusals =
        refusalsOf(
            waitForTheOther -> {
              Roster held = repository.findForUpdate(ACME, draft.id()).orElseThrow();
              waitForTheOther.run();
              awaitWaitingOnALockOr(edit.get(), () -> seenByTheEdit.get() != null);
              repository.save(held.publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT));
            },
            waitForTheOther -> {
              edit.set(backendServingThisTransaction());
              waitForTheOther.run();
              seenByTheEdit.set(repository.findForUpdate(ACME, draft.id()).orElseThrow().status());
            });

    assertThat(refusals).isEmpty();
    assertThat(seenByTheEdit).hasValue(RosterStatus.PUBLISHED);
  }

  /**
   * In autocommit the row would be released the moment the statement reading it ended, so the
   * caller would believe it held a roster nobody was waiting on. Refused instead, as the
   * publication lock is.
   */
  @Test
  void GivenNoTransaction_WhenReadingARosterForUpdate_ThenItIsRefused() {
    Roster draft = draft(ACME);
    repository.save(draft);

    assertThatThrownBy(() -> repository.findForUpdate(ACME, draft.id()))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * A use case named {@code find…} runs read-only, where PostgreSQL refuses to hold a row. Refused
   * here first, in words that name the mistake: a reader asking for what only a writer needs.
   */
  @Test
  void GivenAReadOnlyTransaction_WhenReadingARosterForUpdate_ThenItIsRefused() {
    Roster draft = draft(ACME);
    repository.save(draft);
    TransactionTemplate reading = new TransactionTemplate(PostgresDatabase.transactions());
    reading.setReadOnly(true);

    assertThatThrownBy(
            () ->
                reading.executeWithoutResult(status -> repository.findForUpdate(ACME, draft.id())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void GivenARosterOfAnotherOrganisation_WhenReadForUpdateByUs_ThenItIsNotFound() {
    Roster theirs = draft(RIVAL);
    repository.save(theirs);

    Optional<Roster> found =
        new TransactionTemplate(PostgresDatabase.transactions())
            .execute(status -> repository.findForUpdate(ACME, theirs.id()));

    assertThat(found).isEmpty();
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
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenTwoTransactionsOpenAtOnce_WhenBothPublishOverlappingRosters_ThenExactlyOneIsRefused()
      throws Exception {
    Roster first = draft(ACME);
    Roster second = new Roster(RosterId.newId(), ACME, OVERLAPPING_WEEK);
    repository.save(first);
    repository.save(second);

    List<Throwable> refusals =
        refusalsOf(publishingOnceBothHaveRead(first), publishingOnceBothHaveRead(second));

    assertThat(refusals).singleElement().isInstanceOf(RosterPublicationConflictException.class);
    assertThat(repository.findByOrganisation(ACME))
        .filteredOn(roster -> roster.status() == RosterStatus.PUBLISHED)
        .hasSize(1);
  }

  /**
   * The other way PostgreSQL reports the same collision, forced rather than hoped for.
   *
   * <p>Each writer first publishes a roster the other's does not touch, then one that overlaps only
   * the other's first. Neither can commit before its second save, and each second save waits on the
   * other's uncommitted row, so whichever asks first PostgreSQL has to abort one of them as a
   * deadlock victim. Nothing here depends on timing.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenTwoWritersEachWaitingOnTheOther_WhenBothPublish_ThenTheVictimIsRefusedAsAConflict()
      throws Exception {
    Roster thisWeek = draft(ACME);
    Roster nextWeek = new Roster(RosterId.newId(), ACME, THE_NEXT_WEEK);
    Roster aDayOfNextWeek =
        new Roster(
            RosterId.newId(),
            ACME,
            new TimeRange(
                Instant.parse("2026-09-15T00:00:00Z"), Instant.parse("2026-09-16T00:00:00Z")));
    Roster aDayOfThisWeek =
        new Roster(
            RosterId.newId(),
            ACME,
            new TimeRange(
                Instant.parse("2026-09-08T00:00:00Z"), Instant.parse("2026-09-09T00:00:00Z")));
    for (Roster draft : List.of(thisWeek, nextWeek, aDayOfNextWeek, aDayOfThisWeek)) {
      repository.save(draft);
    }

    List<Throwable> refusals =
        refusalsOf(
            publishingOneThenAnother(thisWeek, aDayOfNextWeek),
            publishingOneThenAnother(nextWeek, aDayOfThisWeek));

    assertThat(refusals).hasSize(1);
    assertThat(refusals.getFirst())
        .isInstanceOf(RosterPublicationConflictException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class,
            cause -> assertThat(cause.getSQLState()).isEqualTo(DEADLOCK_DETECTED));
    assertThat(repository.findByOrganisation(ACME))
        .describedAs("the writer that was not the victim published both of its rosters")
        .filteredOn(roster -> roster.status() == RosterStatus.PUBLISHED)
        .hasSize(2);
  }

  /**
   * A deadlock that has nothing to do with publishing. Two writers each save one draft and then the
   * other's, in opposite orders, so each waits on the row the other holds and PostgreSQL aborts
   * one. Nothing was published, so calling that a publication conflict would be false - and it
   * would be counted as evidence that some writer skipped the publication lock.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenTwoWritersDeadlockedOverDrafts_WhenOneIsAborted_ThenItIsNotAPublicationConflict()
      throws Exception {
    Roster one = draft(ACME);
    Roster other = new Roster(RosterId.newId(), ACME, THE_NEXT_WEEK);
    repository.save(one);
    repository.save(other);

    List<Throwable> refusals =
        refusalsOf(savingOneThenAnother(one, other), savingOneThenAnother(other, one));

    assertThat(refusals).hasSize(1);
    assertThat(refusals.getFirst())
        .isNotInstanceOf(RosterPublicationConflictException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class,
            cause -> assertThat(cause.getSQLState()).isEqualTo(DEADLOCK_DETECTED));
  }

  /** Saves one draft, waits until the other writer has saved one too, then saves another. */
  private Writer savingOneThenAnother(Roster first, Roster then) {
    return waitForTheOther -> {
      repository.save(first);
      waitForTheOther.run();
      repository.save(then);
    };
  }

  /**
   * Reads, waits until the other writer has read too, then publishes. The read is what starts the
   * transaction in PostgreSQL, so after the wait both are open and neither has seen the other.
   */
  private Writer publishingOnceBothHaveRead(Roster draft) {
    return waitForTheOther -> {
      repository.findByOrganisation(ACME);
      waitForTheOther.run();
      repository.save(draft.publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT));
    };
  }

  /** Publishes one roster, waits until the other writer has published one too, then another. */
  private Writer publishingOneThenAnother(Roster first, Roster then) {
    return waitForTheOther -> {
      repository.save(first.publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT));
      waitForTheOther.run();
      repository.save(then.publishedUnder(A_CONSTRAINT_VERSION, PUBLISHED_AT));
    };
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
  @Timeout(value = STUCK_AFTER_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
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
   * Outside a transaction the lock would be released the moment the statement taking it ended, so
   * nothing would wait for anything and the caller would not know. It is refused instead, so a use
   * case that is not wrapped in its transaction fails loudly rather than racing quietly (ADR-027).
   */
  @Test
  void GivenNoTransaction_WhenLockingAnOrganisationsPublications_ThenItIsRefused() {
    assertThatThrownBy(() -> repository.lockPublicationsOf(ACME))
        .isInstanceOf(IllegalStateException.class);
  }

  // --- #250: reading past the driver's bind-parameter limit ------------------------------------

  /**
   * One more roster than pgjdbc can bind parameters in one statement: the wire protocol counts them
   * in 16 bits, so 65,535 is the most. History is append-only and drafts are never deleted, so an
   * organisation reaches this by using the system, and a read binding one parameter per roster then
   * fails for every caller listing them.
   */
  private static final int MORE_ROSTERS_THAN_A_STATEMENT_CAN_BIND = 65_536;

  /**
   * Bulk-inserted in one statement, so the test stays fast; one roster is saved through the adapter
   * with an assignment, so the read-back also shows that children still load.
   */
  @Test
  void
      GivenMoreRostersThanAStatementCanBind_WhenListingTheOrganisations_ThenEachLoadsWithItsWork() {
    Roster assigned =
        draft(ACME).withAssignments(List.of(new Assignment(ShiftId.newId(), EmployeeId.newId())));
    repository.save(assigned);
    PostgresDatabase.jdbcClient()
        .sql(
            """
            INSERT INTO roster (id, organisation_id, starts_at, ends_at)
            SELECT gen_random_uuid(), :organisation, :startsAt, :endsAt
            FROM generate_series(1, :others)
            """)
        .param("organisation", ACME.value())
        .param("startsAt", THE_WEEK.start().atOffset(ZoneOffset.UTC))
        .param("endsAt", THE_WEEK.end().atOffset(ZoneOffset.UTC))
        .param("others", MORE_ROSTERS_THAN_A_STATEMENT_CAN_BIND - 1)
        .update();

    List<Roster> listed = repository.findByOrganisation(ACME);

    assertThat(listed).hasSize(MORE_ROSTERS_THAN_A_STATEMENT_CAN_BIND);
    assertThat(listed)
        .filteredOn(roster -> roster.id().equals(assigned.id()))
        .singleElement()
        .extracting(Roster::assignments)
        .isEqualTo(assigned.assignments());
  }

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
