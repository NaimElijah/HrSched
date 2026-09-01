package io.github.naimelijah.hrsched.workforce.application.service;

import io.github.naimelijah.hrsched.workforce.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.workforce.application.port.out.WorkforceChange;
import java.util.ArrayList;
import java.util.List;

/**
 * Records what a workforce use case says was changed.
 *
 * <p>Hand-written rather than mocked, like every other double here: the audit trail is our own
 * port, and asking it what it was told is how "was this recorded?" becomes a test rather than a
 * review comment.
 *
 * <p>Shared between {@code EmployeeServiceTest} and {@code QualificationServiceTest} rather than
 * duplicated in each. They are in the same package, so a package-private helper costs nothing and
 * crosses no boundary - unlike the scheduling twin, which stays separate because it implements a
 * different context's port over a different change vocabulary (ADR-008).
 *
 * <p>Named without a {@code Test} suffix so Surefire does not try to run it.
 */
final class RecordingAuditTrail implements AuditTrail {

  private final List<AuditedChange> recorded = new ArrayList<>();

  @Override
  public void record(AuditedChange change) {
    recorded.add(change);
  }

  /**
   * @return the kind of every change recorded so far, in order
   */
  List<WorkforceChange> changes() {
    return recorded.stream().map(AuditedChange::change).toList();
  }

  /**
   * @param change the kind of change to select
   * @return every recorded entry of that kind, in order
   */
  List<AuditedChange> of(WorkforceChange change) {
    return recorded.stream().filter(entry -> entry.change() == change).toList();
  }
}
