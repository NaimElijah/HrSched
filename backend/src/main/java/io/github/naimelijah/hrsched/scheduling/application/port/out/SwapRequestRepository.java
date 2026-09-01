package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: storing swap requests.
 *
 * <p>Every lookup is scoped by organisation, so a cross-tenant read cannot be written by forgetting
 * a filter (ADR-009).
 */
public interface SwapRequestRepository {

  void save(SwapRequest request);

  Optional<SwapRequest> findById(OrganisationId organisation, SwapRequestId id);

  /**
   * @param organisation whose requests
   * @return every swap request that organisation holds, oldest first — the order they were raised,
   *     which is the order a Scheduler works through them. Not "newest first", which this said
   *     while its only implementation did the opposite; a swap request carries no time of its own,
   *     so any other ordering would need one adding to the aggregate first
   */
  List<SwapRequest> findByOrganisation(OrganisationId organisation);
}
