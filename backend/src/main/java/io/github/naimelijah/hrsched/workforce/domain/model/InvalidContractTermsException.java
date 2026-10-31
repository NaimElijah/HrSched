package io.github.naimelijah.hrsched.workforce.domain.model;

/**
 * Raised when contract terms would specify a non-positive number of hours.
 *
 * <p>The message carries no hours on purpose: contracted hours say things about a person's
 * household and health, and a message travels further than the record it describes.
 */
public class InvalidContractTermsException extends RuntimeException {

  /**
   * Builds the refusal with a fixed message that names no hours, so it can be the detail of a 400
   * as it stands (ADR-024).
   */
  public InvalidContractTermsException() {
    super("Contracted hours must be positive");
  }
}
