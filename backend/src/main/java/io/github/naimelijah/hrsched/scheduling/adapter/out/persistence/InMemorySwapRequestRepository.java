package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing swap requests in memory.
 *
 * <p>PLACEHOLDER until ADR-004, scoped by organisation on every read.
 *
 * <p>Insertion order is preserved rather than sorted, because a swap request carries no time of its
 * own to sort by. Giving it one would be inventing a field the domain has not been asked for; if an
 * ordering guarantee is ever needed, that is the change to make.
 */
@Repository
public class InMemorySwapRequestRepository implements SwapRequestRepository {

  private final Map<SwapRequestId, SwapRequest> byId = new ConcurrentHashMap<>();
  private final List<SwapRequestId> order =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  @Override
  public void save(SwapRequest request) {
    if (byId.put(request.id(), request) == null) {
      order.add(request.id());
    }
  }

  @Override
  public Optional<SwapRequest> findById(OrganisationId organisation, SwapRequestId id) {
    return Optional.ofNullable(byId.get(id))
        .filter(request -> request.organisation().equals(organisation));
  }

  @Override
  public List<SwapRequest> findByOrganisation(OrganisationId organisation) {
    synchronized (order) {
      return order.stream()
          .map(byId::get)
          .filter(request -> request != null && request.organisation().equals(organisation))
          .toList();
    }
  }
}
