package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-written, like every other double here: the repository is our own port.
 *
 * <p>Shared between SchedulingServiceTest and SwapServiceTest, as {@link FakeRosters} is, because a
 * publication changes swap requests as well as rosters.
 */
final class FakeSwaps implements SwapRequestRepository {

  private final Map<SwapRequestId, SwapRequest> stored = new LinkedHashMap<>();
  private final CallLog log;

  /**
   * @param log shared with the roster store, so reading a request is ordered against the
   *     publication lock too
   */
  FakeSwaps(CallLog log) {
    this.log = log;
  }

  /**
   * Refuses a decision over a decision, as {@code JdbcSwapRequestRepository} does. A fake that
   * accepted it would let a use case pass here and fail in production.
   *
   * <p>The schema's other refusals are not mirrored, because this store sees no roster. {@code
   * SwapService} raises a request only for an assignment it read on its organisation's published
   * roster (R20), and checks for a pending one first (R32), so none is reachable through it. Each
   * is proven in {@code JdbcSwapRequestRepositoryTest}:
   *
   * <ul>
   *   <li>an identity another organisation holds: {@code
   *       GivenAPendingRequestOfAnotherOrganisation_WhenSavingOverIt_ThenItIsRefused};
   *   <li>a second pending request for one assignment: {@code
   *       GivenAPendingRequest_WhenASecondIsStoredForTheSameAssignment_ThenTheSchemaRefusesIt};
   *   <li>a request naming a roster of another organisation (V14): {@code
   *       GivenARosterOfAnotherOrganisation_WhenARequestNamingItIsStored_ThenTheSchemaRefusesIt};
   *   <li>a request naming a roster never stored (V14): {@code
   *       GivenARosterNeverStored_WhenARequestNamingItIsStored_ThenTheSchemaRefusesIt};
   *   <li>a request naming an assignment its roster does not hold (V14): {@code
   *       GivenAnAssignmentTheRosterDoesNotHold_WhenARequestNamingItIsStored_ThenTheSchemaRefusesIt}.
   * </ul>
   */
  @Override
  public void save(SwapRequest request) {
    SwapRequest before = stored.get(request.id());
    if (before != null && before.status() != SwapRequestStatus.REQUESTED) {
      throw new SwapNotPendingException(request.id(), before.status());
    }
    stored.put(request.id(), request);
  }

  @Override
  public Optional<SwapRequest> findById(OrganisationId organisation, SwapRequestId id) {
    log.add(CallLog.Call.FIND_SWAP_REQUEST);
    return Optional.ofNullable(stored.get(id))
        .filter(request -> request.organisation().equals(organisation));
  }

  @Override
  public List<SwapRequest> findByOrganisation(OrganisationId organisation) {
    log.add(CallLog.Call.FIND_SWAP_REQUESTS);
    return stored.values().stream()
        .filter(request -> request.organisation().equals(organisation))
        .toList();
  }

  @Override
  public List<SwapRequest> findPendingNaming(OrganisationId organisation, RosterId roster) {
    log.add(CallLog.Call.FIND_PENDING_SWAP_REQUESTS);
    return stored.values().stream()
        .filter(request -> request.organisation().equals(organisation))
        .filter(request -> request.roster().equals(roster))
        .filter(request -> request.status() == SwapRequestStatus.REQUESTED)
        .sorted(Comparator.comparing(SwapRequest::requestedAt))
        .toList();
  }

  SwapRequest byId(SwapRequestId id) {
    return stored.get(id);
  }
}
