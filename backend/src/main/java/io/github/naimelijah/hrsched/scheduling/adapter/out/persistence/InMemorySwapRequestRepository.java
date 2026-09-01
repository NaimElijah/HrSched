package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing swap requests in memory.
 *
 * <p>PLACEHOLDER until ADR-004, scoped by organisation on every read.
 *
 * <p>Requests come back in the order they were raised, which is what the port promises and what a
 * Scheduler working through a queue wants. A swap request carries no time of its own, so any other
 * ordering would mean inventing a field the domain has not been asked for.
 */
@Repository
public class InMemorySwapRequestRepository implements SwapRequestRepository {

  /**
   * A synchronised {@link LinkedHashMap} rather than a {@code ConcurrentHashMap}, which is what the
   * sibling repositories use: those either sort on a field or promise no order, and this one has no
   * field to sort on, so the insertion order the map keeps <em>is</em> the contract.
   */
  private final Map<SwapRequestId, SwapRequest> byId =
      Collections.synchronizedMap(new LinkedHashMap<>());

  @Override
  public void save(SwapRequest request) {
    byId.put(request.id(), request);
  }

  @Override
  public Optional<SwapRequest> findById(OrganisationId organisation, SwapRequestId id) {
    return Optional.ofNullable(byId.get(id))
        .filter(request -> request.organisation().equals(organisation));
  }

  @Override
  public List<SwapRequest> findByOrganisation(OrganisationId organisation) {
    synchronized (byId) {
      return byId.values().stream()
          .filter(request -> request.organisation().equals(organisation))
          .toList();
    }
  }
}
