package io.github.naimelijah.hrsched.scheduling.application.port.out;

/**
 * Where scheduling records what was changed and by whom.
 *
 * <p>A driven port rather than a logger, because the application layer may hold no framework and no
 * instrumentation library, and because "was this recorded?" then becomes something a test can ask
 * instead of something a reviewer has to notice.
 *
 * <p>Deliberately not shared with the other contexts. The changes worth recording differ per
 * context, so each context's port is a different type rather than one duplicated — and anything
 * shared would have to live in the kernel, which ADR-008 keeps as small as it can be.
 */
public interface AuditTrail {

  /**
   * Records a change that has already happened.
   *
   * @param change what changed, in which organisation, and who did it
   */
  void record(AuditedChange change);
}
