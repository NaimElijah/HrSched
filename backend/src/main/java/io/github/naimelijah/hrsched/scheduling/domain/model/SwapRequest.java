package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.time.Instant;
import java.util.Objects;

/**
 * One person asking for a shift of theirs to be covered by a named colleague.
 *
 * <p>An entity: a request that ended and another raised later about the same shift by the same pair
 * are still two requests, each with its own outcome, so equality is by {@link SwapRequestId} alone.
 * Only one of them may be pending at a time (R32). Immutable, like every aggregate here — deciding
 * one returns a new instance.
 *
 * <p><strong>This models a transfer, not a two-way exchange.</strong> The requester's assignment
 * moves to the counterparty, and nothing of the counterparty's moves back. F7 says "request a swap
 * of one of their assignments with another employee", which reads either way; this is the narrower
 * reading, it is the one that composes with the roster rules without inventing anything, and it is
 * recorded as <strong>Q22</strong> rather than presented as settled. See ADR-022.
 *
 * <p>It names a roster and a shift rather than an {@code Assignment}, because the assignment it
 * refers to has to still be there when a Scheduler answers — and whether it is is a question for
 * the application layer, which can read the roster, rather than for this record, which cannot.
 *
 * <p>It carries {@code requestedAt} because the repository promises requests in the order they were
 * raised, which is the order a Scheduler works through them. A store that happened to keep
 * insertion order could honour that without the field; a store that does not, cannot. Making the
 * time a fact about the request rather than an accident of the map it sat in is what lets the
 * promise be kept by anything (ADR-004).
 */
public final class SwapRequest {

  private final SwapRequestId id;
  private final OrganisationId organisation;
  private final RosterId roster;
  private final ShiftId shift;
  private final EmployeeId requester;
  private final EmployeeId counterparty;
  private final SwapRequestStatus status;
  private final Instant requestedAt;

  /**
   * Creates a request waiting on a Scheduler.
   *
   * @param id the request's identity
   * @param organisation whose request this is
   * @param roster the published roster the shift is on
   * @param shift the shift being handed over
   * @param requester whoever currently covers it
   * @param counterparty whoever is being asked to take it
   * @param requestedAt when it was raised, from the caller's clock rather than a store's
   * @throws NullPointerException if any argument is null
   * @throws SwapWithThemselfException if the two are the same person
   */
  public SwapRequest(
      SwapRequestId id,
      OrganisationId organisation,
      RosterId roster,
      ShiftId shift,
      EmployeeId requester,
      EmployeeId counterparty,
      Instant requestedAt) {
    this(
        id,
        organisation,
        roster,
        shift,
        requester,
        counterparty,
        SwapRequestStatus.REQUESTED,
        requestedAt);
  }

  private SwapRequest(
      SwapRequestId id,
      OrganisationId organisation,
      RosterId roster,
      ShiftId shift,
      EmployeeId requester,
      EmployeeId counterparty,
      SwapRequestStatus status,
      Instant requestedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organisation = Objects.requireNonNull(organisation, "organisation must not be null");
    this.roster = Objects.requireNonNull(roster, "roster must not be null");
    this.shift = Objects.requireNonNull(shift, "shift must not be null");
    this.requester = Objects.requireNonNull(requester, "requester must not be null");
    this.counterparty = Objects.requireNonNull(counterparty, "counterparty must not be null");
    if (requester.equals(counterparty)) {
      throw new SwapWithThemselfException();
    }
    this.status = Objects.requireNonNull(status, "status must not be null");
    this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
  }

  /**
   * @return this request marked as agreed
   * @throws SwapNotPendingException if it has already been decided
   */
  public SwapRequest approved() {
    return decided(SwapRequestStatus.APPROVED);
  }

  /**
   * @return this request marked as declined
   * @throws SwapNotPendingException if it has already been decided
   */
  public SwapRequest rejected() {
    return decided(SwapRequestStatus.REJECTED);
  }

  /**
   * @return this request marked as lapsed, because the roster it names was superseded before
   *     anybody decided it (R33)
   * @throws SwapNotPendingException if it has already been decided
   */
  public SwapRequest lapsed() {
    return decided(SwapRequestStatus.LAPSED);
  }

  private SwapRequest decided(SwapRequestStatus outcome) {
    if (status != SwapRequestStatus.REQUESTED) {
      throw new SwapNotPendingException(id, status);
    }
    return new SwapRequest(
        id, organisation, roster, shift, requester, counterparty, outcome, requestedAt);
  }

  /**
   * @param employee somebody who might be involved
   * @return whether this request is theirs to see — either side of it, and nobody else
   */
  public boolean involves(EmployeeId employee) {
    Objects.requireNonNull(employee, "employee must not be null");
    return requester.equals(employee) || counterparty.equals(employee);
  }

  /**
   * @return the assignment this request would take off the roster
   */
  public Assignment given() {
    return new Assignment(shift, requester);
  }

  /**
   * @return the assignment this request would put on it instead
   */
  public Assignment taken() {
    return new Assignment(shift, counterparty);
  }

  /**
   * @return this request's identity, the only thing its equality compares
   */
  public SwapRequestId id() {
    return id;
  }

  /**
   * @return the organisation this request belongs to
   */
  public OrganisationId organisation() {
    return organisation;
  }

  /**
   * @return the published roster the request was raised against
   */
  public RosterId roster() {
    return roster;
  }

  /**
   * @return the shift being handed over
   */
  public ShiftId shift() {
    return shift;
  }

  /**
   * @return whoever covered the shift when the request was raised and is handing it over
   */
  public EmployeeId requester() {
    return requester;
  }

  /**
   * @return whoever is asked to take the shift over
   */
  public EmployeeId counterparty() {
    return counterparty;
  }

  /**
   * @return where the request stands; only {@link SwapRequestStatus#REQUESTED} still awaits a
   *     decision
   */
  public SwapRequestStatus status() {
    return status;
  }

  /**
   * @return when the request was raised; unchanged by deciding it
   */
  public Instant requestedAt() {
    return requestedAt;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SwapRequest request && id.equals(request.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  /** Names neither party: a {@code toString} ends up in places a field does not. */
  @Override
  public String toString() {
    return "SwapRequest[id=%s, status=%s]".formatted(id, status);
  }
}
