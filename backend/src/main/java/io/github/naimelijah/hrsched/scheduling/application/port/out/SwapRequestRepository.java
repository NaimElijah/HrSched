package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
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

  /**
   * Stores a request, or a decision about one.
   *
   * @param request the request as it now stands
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException if the
   *     stored request has already left {@code REQUESTED}. A decision is final, so a second one is
   *     refused rather than written over the first, whichever writer read it pending
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.SwapAlreadyRequestedException if
   *     it is pending and its assignment already has another pending request (R32). Raising a
   *     request checks first; this is the backstop for a writer that skipped the publication lock
   */
  void save(SwapRequest request);

  /**
   * @param organisation whose request; another organisation's is never found
   * @param id which request
   * @return the request, empty if this organisation holds none with that identity
   */
  Optional<SwapRequest> findById(OrganisationId organisation, SwapRequestId id);

  /**
   * @param organisation whose requests
   * @return every swap request that organisation holds, oldest first — the order they were raised,
   *     which is the order a Scheduler works through them. Ordered by {@code requestedAt}, so the
   *     promise does not rest on a store that happens to keep insertion order, which no store owes
   *     anybody (ADR-004)
   */
  List<SwapRequest> findByOrganisation(OrganisationId organisation);

  /**
   * What a publication lapses when it supersedes a roster (R33).
   *
   * @param organisation whose requests; another organisation's are never returned
   * @param roster the roster the requests name
   * @return every request still {@code REQUESTED} that names that roster, oldest first
   */
  List<SwapRequest> findPendingNaming(OrganisationId organisation, RosterId roster);
}
