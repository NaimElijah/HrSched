package io.github.naimelijah.hrsched.workforce.domain.model;

import java.time.Duration;

/** Raised when contract terms would specify a non-positive number of hours. */
public class InvalidContractTermsException extends RuntimeException {

  public InvalidContractTermsException(Duration weeklyContractedHours) {
    super("Contracted hours must be positive, but got %s".formatted(weeklyContractedHours));
  }
}
