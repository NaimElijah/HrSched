package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Comparator;
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
 * <p>Requests come back oldest first, which is what the port promises and what a Scheduler working
 * through a queue wants. Sorted on {@code requestedAt}, with the identity as a tie-break so two
 * requests raised in the same instant still come back in a stable order.
 */
@Repository
public class InMemorySwapRequestRepository implements SwapRequestRepository {

  private final Map<SwapRequestId, SwapRequest> byId = new ConcurrentHashMap<>();

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
    return byId.values().stream()
        .filter(request -> request.organisation().equals(organisation))
        .sorted(
            Comparator.comparing(SwapRequest::requestedAt)
                .thenComparing(request -> request.id().value()))
        .toList();
  }
}
