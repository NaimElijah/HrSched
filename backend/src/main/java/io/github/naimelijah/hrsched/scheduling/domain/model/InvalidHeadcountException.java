package io.github.naimelijah.hrsched.scheduling.domain.model;

/** Raised when a shift would require fewer than one person. */
public class InvalidHeadcountException extends RuntimeException {

  public InvalidHeadcountException(int requested) {
    super("A shift must require at least one person, but got " + requested);
  }
}
