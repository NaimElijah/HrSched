package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a roster cannot be published because another roster covering part of the same period
 * was being published at the same time (R11).
 *
 * <p>Exactly one roster is authoritative for any moment, and the schema holds that even against a
 * writer that could not see the other publication because it had not yet committed (ADR-026).
 * Nothing about the request was wrong - it arrived while somebody else's publication was still in
 * flight - so it is a 409 rather than a 400: reload what is now published, and try again if it is
 * still wanted.
 */
public class RosterPublicationConflictException extends RuntimeException {

  private final transient RosterId roster;

  /**
   * @param roster the roster whose publication was refused
   * @param cause what the store reported, kept for whoever reads the log rather than the response
   */
  public RosterPublicationConflictException(RosterId roster, Throwable cause) {
    super(
        "A roster covering part of the same period was published at the same time; reload and try"
            + " again",
        cause);
    this.roster = roster;
  }

  /**
   * @return the roster whose publication was refused. Deliberately not in the message: when a swap
   *     approval loses, it is a replacement the caller never saw, rolled back with the refusal
   */
  public RosterId roster() {
    return roster;
  }
}
