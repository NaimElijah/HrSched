package io.github.naimelijah.hrsched.scheduling.application.service;

import java.util.ArrayList;
import java.util.List;

/**
 * The order in which a use case called the fakes that share this log.
 *
 * <p>Kept for one question: whether the publication lock was taken before the reads a publication
 * decides from. Those reads go through different ports, so one log has to see all of them. Saves
 * are not recorded, because nothing depends on their order.
 */
final class CallLog {

  /** A lock taken, or a read made, through one of the fakes sharing the log. */
  enum Call {
    LOCK_PUBLICATIONS,
    FIND_ROSTER,
    FIND_ROSTERS,
    FIND_SWAP_REQUEST,
    FIND_SWAP_REQUESTS,
    FIND_PENDING_SWAP_REQUESTS
  }

  private final List<Call> calls = new ArrayList<>();

  void add(Call call) {
    calls.add(call);
  }

  /**
   * @return every call since the last {@link #forget()}, in the order they were made
   */
  List<Call> calls() {
    return List.copyOf(calls);
  }

  /** Starts afresh, so a test sees only what the call under test asked for. */
  void forget() {
    calls.clear();
  }
}
