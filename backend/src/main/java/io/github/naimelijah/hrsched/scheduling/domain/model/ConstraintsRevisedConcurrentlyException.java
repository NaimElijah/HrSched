package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a revision of an organisation's rules cannot be stored because another revision took
 * the same next version first.
 *
 * <p>Two Admins revising at once both read the same current rules and compute the same next
 * version. The store keeps the first and refuses the second rather than storing it over the first
 * (ADR-023). Nothing about the revision was wrong, as it arrived while somebody else's was in
 * flight, so it is a 409 rather than a 400: reload the rules, and revise them again if it is still
 * wanted.
 */
public class ConstraintsRevisedConcurrentlyException extends RuntimeException {

  /**
   * @param version the version both revisions would have taken
   * @param cause what the store reported, kept for whoever reads the log rather than the response
   */
  public ConstraintsRevisedConcurrentlyException(int version, Throwable cause) {
    super(
        "Version %d of the rules was stored by another revision at the same time; reload them and"
                .formatted(version)
            + " revise again",
        cause);
  }
}
