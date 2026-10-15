package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapAlreadyRequestedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tenant predicate and the ordering this adapter promises.
 *
 * <p>The first adapter test this port has ever had. Its ordering promise — oldest first, the order
 * a Scheduler works through the queue — used to rest on a store that happened to keep insertion
 * order, and nothing asserted it. It now rests on {@code requestedAt}, and these tests are what
 * says so.
 */
class JdbcSwapRequestRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final Instant NINE = Instant.parse("2026-10-01T09:00:00Z");
  private static final Instant TEN = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant ELEVEN = Instant.parse("2026-10-01T11:00:00Z");

  private final JdbcSwapRequestRepository repository =
      new JdbcSwapRequestRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
    PostgresDatabase.given(ACME, RIVAL);
  }

  private static SwapRequest request(OrganisationId organisation, Instant raisedAt) {
    return new SwapRequest(
        SwapRequestId.newId(),
        organisation,
        RosterId.newId(),
        ShiftId.newId(),
        EmployeeId.newId(),
        EmployeeId.newId(),
        raisedAt);
  }

  @Test
  void GivenARequest_WhenReadBackByItsOwnOrganisation_ThenItIsFound() {
    SwapRequest ours = request(ACME, TEN);
    repository.save(ours);

    assertThat(repository.findById(ACME, ours.id())).contains(ours);
  }

  @Test
  void GivenARequestOfAnotherOrganisation_WhenReadBackByUs_ThenItIsNotFound() {
    SwapRequest theirs = request(RIVAL, TEN);
    repository.save(theirs);

    assertThat(repository.findById(ACME, theirs.id())).isEmpty();
  }

  @Test
  void GivenNoSuchRequest_WhenReadingIt_ThenNothingIsFound() {
    assertThat(repository.findById(ACME, SwapRequestId.newId())).isEmpty();
  }

  @Test
  void GivenTwoOrganisations_WhenListingOne_ThenOnlyItsOwnRequestsComeBack() {
    SwapRequest ours = request(ACME, TEN);
    repository.save(ours);
    repository.save(request(RIVAL, TEN));

    assertThat(repository.findByOrganisation(ACME)).containsExactly(ours);
  }

  @Test
  void GivenSeveralRequests_WhenListingThem_ThenTheOldestComesFirst() {
    SwapRequest late = request(ACME, ELEVEN);
    SwapRequest early = request(ACME, NINE);
    SwapRequest middle = request(ACME, TEN);
    repository.save(late);
    repository.save(early);
    repository.save(middle);

    assertThat(repository.findByOrganisation(ACME)).containsExactly(early, middle, late);
  }

  /**
   * Two raised in the same instant still need a stable order, or a Scheduler's queue reshuffles on
   * refresh. Asserted against the identity tie-break rather than against a second read of the same
   * query: two identical reads agree whether or not the tie-break exists, so that would have been a
   * test incapable of failing.
   *
   * <p>The expected order is computed with an <em>unsigned</em> comparison, because {@code
   * UUID.compareTo} is not the ordering the database uses: Java compares the high bits as signed
   * longs, so an identity beginning with a byte of 0x80 or more sorts before every other, while
   * PostgreSQL compares a {@code uuid} as unsigned bytes and sorts it after. Sorting with {@code
   * Comparator.comparing(UUID::compareTo)} here disagrees with the query about half the time.
   */
  @Test
  void GivenTwoRequestsRaisedInTheSameInstant_WhenListed_ThenTheIdentityBreaksTheTie() {
    SwapRequest first = request(ACME, TEN);
    SwapRequest second = request(ACME, TEN);
    repository.save(first);
    repository.save(second);
    List<SwapRequest> asTheDatabaseOrdersThem =
        Stream.of(first, second).sorted(byUnsignedIdentity()).toList();

    assertThat(repository.findByOrganisation(ACME))
        .containsExactlyElementsOf(asTheDatabaseOrdersThem);
  }

  private static Comparator<SwapRequest> byUnsignedIdentity() {
    return Comparator.comparing(
        request -> request.id().value(),
        Comparator.comparingLong((UUID id) -> Long.MIN_VALUE ^ id.getMostSignificantBits())
            .thenComparingLong(id -> Long.MIN_VALUE ^ id.getLeastSignificantBits()));
  }

  @Test
  void GivenADecidedRequest_WhenReadBack_ThenItKeepsItsDecision() {
    SwapRequest approved = request(ACME, TEN).approved();
    repository.save(approved);

    assertThat(repository.findById(ACME, approved.id()))
        .get()
        .extracting(SwapRequest::status)
        .isEqualTo(SwapRequestStatus.APPROVED);
  }

  @Test
  void GivenARejectedRequest_WhenReadBack_ThenItKeepsItsDecision() {
    SwapRequest rejected = request(ACME, TEN).rejected();
    repository.save(rejected);

    assertThat(repository.findById(ACME, rejected.id()))
        .get()
        .extracting(SwapRequest::status)
        .isEqualTo(SwapRequestStatus.REJECTED);
  }

  @Test
  void GivenALapsedRequest_WhenReadBack_ThenItKeepsItsLapse() {
    SwapRequest lapsed = request(ACME, TEN).lapsed();
    repository.save(lapsed);

    assertThat(repository.findById(ACME, lapsed.id()))
        .get()
        .extracting(SwapRequest::status)
        .isEqualTo(SwapRequestStatus.LAPSED);
  }

  /**
   * What a publication lapses when it supersedes a roster (R33): the requests still waiting on that
   * roster, oldest first, and none that were already decided or name another roster.
   */
  @Test
  void GivenRequestsOnSeveralRosters_WhenFindingThosePendingOnOne_ThenOnlyItsWaitingOnesComeBack() {
    RosterId superseded = RosterId.newId();
    SwapRequest later = naming(ACME, superseded, ELEVEN);
    SwapRequest earlier = naming(ACME, superseded, NINE);
    repository.save(later);
    repository.save(earlier);
    repository.save(naming(ACME, superseded, TEN).rejected());
    repository.save(naming(ACME, RosterId.newId(), TEN));

    assertThat(repository.findPendingNaming(ACME, superseded)).containsExactly(earlier, later);
  }

  /**
   * One test per tenancy decision, not a table (CLAUDE.md). A roster's identity is never shared
   * between organisations, so another organisation's request naming it is a forged one; it must
   * still never come back, nor be lapsed by our publication.
   */
  @Test
  void GivenAnotherOrganisationsRequestNamingTheRoster_WhenFindingOursPending_ThenItIsNotThere() {
    RosterId roster = RosterId.newId();
    repository.save(naming(RIVAL, roster, TEN));

    assertThat(repository.findPendingNaming(ACME, roster)).isEmpty();
  }

  /**
   * The schema backstop for R32, as {@code roster_one_published_per_moment} is for R11. Raising a
   * request checks for a pending one under the publication lock; a writer that skipped the lock is
   * refused here instead, with the same refusal.
   */
  @Test
  void GivenAPendingRequest_WhenASecondIsStoredForTheSameAssignment_ThenTheSchemaRefusesIt() {
    SwapRequest first = request(ACME, NINE);
    repository.save(first);

    assertThatThrownBy(() -> repository.save(sameAssignment(first, TEN)))
        .isInstanceOf(SwapAlreadyRequestedException.class);
    assertThat(repository.findByOrganisation(ACME)).containsExactly(first);
  }

  /** Only a pending request holds its assignment; once decided, another may be raised for it. */
  @Test
  void GivenADecidedRequest_WhenASecondIsStoredForTheSameAssignment_ThenItIsStored() {
    SwapRequest first = request(ACME, NINE);
    repository.save(first);
    repository.save(first.rejected());
    SwapRequest second = sameAssignment(first, TEN);

    repository.save(second);

    assertThat(repository.findById(ACME, second.id())).contains(second);
  }

  /** Another request for the same roster, shift and requester; whom it asks does not matter. */
  private static SwapRequest sameAssignment(SwapRequest of, Instant raisedAt) {
    return new SwapRequest(
        SwapRequestId.newId(),
        of.organisation(),
        of.roster(),
        of.shift(),
        of.requester(),
        EmployeeId.newId(),
        raisedAt);
  }

  private static SwapRequest naming(
      OrganisationId organisation, RosterId roster, Instant raisedAt) {
    return new SwapRequest(
        SwapRequestId.newId(),
        organisation,
        roster,
        ShiftId.newId(),
        EmployeeId.newId(),
        EmployeeId.newId(),
        raisedAt);
  }

  @Test
  void GivenARequest_WhenReadBack_ThenItKeepsTheTimeItWasRaised() {
    SwapRequest ours = request(ACME, TEN);
    repository.save(ours);

    assertThat(repository.findById(ACME, ours.id()))
        .get()
        .extracting(SwapRequest::requestedAt)
        .isEqualTo(TEN);
  }

  /** Deciding a request re-saves it, and must not leave a second row nor move it in the queue. */
  @Test
  void GivenADecidedRequest_WhenSavedOverItsPendingSelf_ThenThereIsOneOfItInTheSamePlace() {
    SwapRequest first = request(ACME, NINE);
    SwapRequest second = request(ACME, TEN);
    repository.save(first);
    repository.save(second);

    repository.save(first.approved());

    assertThat(repository.findByOrganisation(ACME))
        .extracting(SwapRequest::id, SwapRequest::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(first.id(), SwapRequestStatus.APPROVED),
            org.assertj.core.groups.Tuple.tuple(second.id(), SwapRequestStatus.REQUESTED));
  }

  /**
   * A decision is final in the store as well as in the domain. Two writers can each read a request
   * while it is pending and each decide it: two Schedulers, or a Scheduler and a publication that
   * lapses it. The second must be refused rather than overwrite the first, whose parties have
   * already been told. The guard is in the statement, so a writer that waited on the row is judged
   * against what the first one committed.
   */
  @Test
  void GivenADecidedRequest_WhenAnotherDecisionIsSavedOverIt_ThenItIsRefusedAsNoLongerPending() {
    SwapRequest pending = request(ACME, TEN);
    repository.save(pending);
    repository.save(pending.approved());

    assertThatThrownBy(() -> repository.save(pending.rejected()))
        .isInstanceOf(SwapNotPendingException.class)
        .hasMessageContaining("APPROVED");
  }

  @Test
  void GivenADecidedRequest_WhenAnotherDecisionIsRefused_ThenTheStoredDecisionStands() {
    SwapRequest pending = request(ACME, TEN);
    repository.save(pending);
    repository.save(pending.approved());

    assertThatThrownBy(() -> repository.save(pending.rejected()));

    assertThat(repository.findById(ACME, pending.id()))
        .get()
        .extracting(SwapRequest::status)
        .isEqualTo(SwapRequestStatus.APPROVED);
  }

  /**
   * The identity guard still answers as it did. Refusing a final state reads the stored row back to
   * name its status, and that read is scoped too, so another organisation's row is never described.
   */
  @Test
  void
      GivenAnIdentityHeldByAnotherOrganisation_WhenSavedOverIt_ThenItIsRefusedWithoutNamingTheirs() {
    SwapRequest theirs = request(RIVAL, TEN).approved();
    repository.save(theirs);
    SwapRequest ours =
        new SwapRequest(
            theirs.id(),
            ACME,
            theirs.roster(),
            theirs.shift(),
            theirs.requester(),
            theirs.counterparty(),
            TEN);

    assertThatThrownBy(() -> repository.save(ours))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("APPROVED");
    assertThat(repository.findById(RIVAL, theirs.id()))
        .get()
        .extracting(SwapRequest::status)
        .isEqualTo(SwapRequestStatus.APPROVED);
  }

  /**
   * {@code timestamptz} holds microseconds and an {@link Instant} holds nanoseconds, so a clock
   * finer than the column does not survive the round trip — and it is <em>rounded</em>, not
   * truncated: {@code …123456789} comes back as {@code …123457}, a later instant than the one
   * stored. Pinned here because every other test in this class uses whole seconds and so none of
   * them can see it, and because the ordering this adapter promises is built on this column: a
   * caller comparing what it stored against what it read back would otherwise find them unequal for
   * reasons nothing documents.
   */
  @Test
  void GivenATimeFinerThanTheColumn_WhenReadBack_ThenItIsRoundedToTheNearestMicrosecond() {
    SwapRequest ours = request(ACME, Instant.parse("2026-10-01T10:00:00Z").plusNanos(123456789));
    repository.save(ours);

    assertThat(repository.findById(ACME, ours.id()))
        .get()
        .extracting(SwapRequest::requestedAt)
        .isEqualTo(Instant.parse("2026-10-01T10:00:00.123457Z"));
  }

  @Test
  void GivenAnEmptyStore_WhenListing_ThenTheListIsEmptyRatherThanNull() {
    assertThat(repository.findByOrganisation(ACME)).isNotNull().isEmpty();
  }
}
