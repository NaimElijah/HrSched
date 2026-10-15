package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
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

  @Override
  public void save(SwapRequest request) {
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

  SwapRequest byId(SwapRequestId id) {
    return stored.get(id);
  }
}
