package io.github.naimelijah.hrsched.access.application.port.out;

/**
 * Where access records what was changed and by whom.
 *
 * <p>A driven port rather than a logger, because the application layer may hold no framework and no
 * instrumentation library, and because "was this recorded?" then becomes something a test can ask
 * instead of something a reviewer has to notice.
 *
 * <p>Deliberately not shared with {@code workforce} or {@code scheduling}; see {@link
 * AuditedChange}.
 */
public interface AuditTrail {

  /**
   * Records a change that has already happened.
   *
   * @param change what changed, in which organisation, and who did it
   */
  void record(AuditedChange change);
}
