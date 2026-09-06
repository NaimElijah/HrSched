package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.Instant;
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

  /** Two raised in the same instant still need a stable order, or a queue reshuffles on refresh. */
  @Test
  void GivenTwoRequestsRaisedTogether_WhenListedTwice_ThenTheOrderIsTheSameBothTimes() {
    repository.save(request(ACME, TEN));
    repository.save(request(ACME, TEN));

    assertThat(repository.findByOrganisation(ACME))
        .containsExactlyElementsOf(repository.findByOrganisation(ACME));
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

  @Test
  void GivenAnEmptyStore_WhenListing_ThenTheListIsEmptyRatherThanNull() {
    assertThat(repository.findByOrganisation(ACME)).isNotNull().isEmpty();
  }
}
