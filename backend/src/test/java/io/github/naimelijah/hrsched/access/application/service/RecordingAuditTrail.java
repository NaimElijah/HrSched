package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import java.util.ArrayList;
import java.util.List;

/**
 * Records what an access use case says was changed.
 *
 * <p>Hand-written rather than mocked, like every other double here: the audit trail is our own
 * port, and asking it what it was told is how "was this recorded?" becomes a test rather than a
 * review comment.
 *
 * <p>A third copy rather than a shared one, for the reason ADR-008 gives: it implements this
 * context's port over this context's change vocabulary, and the three are different types rather
 * than duplication.
 *
 * <p>Named without a {@code Test} suffix so Surefire does not try to run it.
 */
final class RecordingAuditTrail implements AuditTrail {

  private final List<AuditedChange> recorded = new ArrayList<>();

  @Override
  public void record(AuditedChange change) {
    recorded.add(change);
  }

  /** Drops everything recorded so far, so a later assertion is about later behaviour alone. */
  void forget() {
    recorded.clear();
  }

  /**
   * @return the kind of every change recorded so far, in order
   */
  List<AccessChange> changes() {
    return recorded.stream().map(AuditedChange::change).toList();
  }

  /**
   * @param change the kind of change to select
   * @return every recorded entry of that kind, in order
   */
  List<AuditedChange> of(AccessChange change) {
    return recorded.stream().filter(entry -> entry.change() == change).toList();
  }
}
