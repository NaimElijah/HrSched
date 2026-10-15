package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The invariants a swap request owns, and what deciding one does and does not change.
 *
 * <p>The time it was raised is load-bearing rather than decorative: {@code SwapRequestRepository}
 * orders a Scheduler's queue by it, so a decision that dropped or moved it would silently reorder
 * the queue somebody is working through (ADR-004).
 */
class SwapRequestTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final Instant RAISED_AT = Instant.parse("2026-10-01T09:30:00Z");

  private static SwapRequest request() {
    return new SwapRequest(
        SwapRequestId.newId(),
        ACME,
        RosterId.newId(),
        ShiftId.newId(),
        EmployeeId.newId(),
        EmployeeId.newId(),
        RAISED_AT);
  }

  @Test
  void GivenATimeItWasRaised_WhenConstructingARequest_ThenItCarriesThatTime() {
    assertThat(request().requestedAt()).isEqualTo(RAISED_AT);
  }

  @Test
  void GivenNoTime_WhenConstructingARequest_ThenItIsRejected() {
    assertThatThrownBy(
            () ->
                new SwapRequest(
                    SwapRequestId.newId(),
                    ACME,
                    RosterId.newId(),
                    ShiftId.newId(),
                    EmployeeId.newId(),
                    EmployeeId.newId(),
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("requestedAt");
  }

  @Test
  void GivenAPendingRequest_WhenApproved_ThenTheTimeItWasRaisedIsUnchanged() {
    SwapRequest pending = request();

    assertThat(pending.approved().requestedAt()).isEqualTo(RAISED_AT);
  }

  @Test
  void GivenAPendingRequest_WhenRejected_ThenTheTimeItWasRaisedIsUnchanged() {
    SwapRequest pending = request();

    assertThat(pending.rejected().requestedAt()).isEqualTo(RAISED_AT);
  }

  @Test
  void GivenAPendingRequest_WhenApproved_ThenOnlyTheStatusChanges() {
    SwapRequest pending = request();

    SwapRequest approved = pending.approved();

    assertThat(approved.status()).isEqualTo(SwapRequestStatus.APPROVED);
    assertThat(approved.id()).isEqualTo(pending.id());
    assertThat(approved.organisation()).isEqualTo(pending.organisation());
    assertThat(approved.roster()).isEqualTo(pending.roster());
    assertThat(approved.shift()).isEqualTo(pending.shift());
    assertThat(approved.requester()).isEqualTo(pending.requester());
    assertThat(approved.counterparty()).isEqualTo(pending.counterparty());
  }

  /** R33: nobody decided it, but it is over, and it keeps everything that identified it. */
  @Test
  void GivenAPendingRequest_WhenItLapses_ThenOnlyTheStatusChanges() {
    SwapRequest pending = request();

    SwapRequest lapsed = pending.lapsed();

    assertThat(lapsed.status()).isEqualTo(SwapRequestStatus.LAPSED);
    assertThat(lapsed.id()).isEqualTo(pending.id());
    assertThat(lapsed.roster()).isEqualTo(pending.roster());
    assertThat(lapsed.shift()).isEqualTo(pending.shift());
    assertThat(lapsed.requester()).isEqualTo(pending.requester());
    assertThat(lapsed.counterparty()).isEqualTo(pending.counterparty());
    assertThat(lapsed.requestedAt()).isEqualTo(RAISED_AT);
  }

  /** An approved swap already published its roster; its roster moving on does not undo that. */
  @Test
  void GivenAnApprovedRequest_WhenItWouldLapse_ThenItIsRefused() {
    SwapRequest approved = request().approved();

    assertThatThrownBy(approved::lapsed).isInstanceOf(SwapNotPendingException.class);
  }

  @Test
  void GivenARejectedRequest_WhenItWouldLapse_ThenItIsRefused() {
    SwapRequest rejected = request().rejected();

    assertThatThrownBy(rejected::lapsed).isInstanceOf(SwapNotPendingException.class);
  }

  /**
   * A lapsed request names a roster that no longer governs, so there is nothing left to approve.
   */
  @Test
  void GivenALapsedRequest_WhenApproved_ThenItIsRefused() {
    SwapRequest lapsed = request().lapsed();

    assertThatThrownBy(lapsed::approved).isInstanceOf(SwapNotPendingException.class);
  }
}
