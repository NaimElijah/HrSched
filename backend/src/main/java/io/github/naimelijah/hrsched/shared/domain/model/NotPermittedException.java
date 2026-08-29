package io.github.naimelijah.hrsched.shared.domain.model;

import java.util.Set;

/** Raised when an actor holds the wrong role for an operation. */
public class NotPermittedException extends RuntimeException {

  public NotPermittedException(UserId actor, Role held, Set<Role> permitted) {
    super(
        "Actor %s holds %s, but this requires one of %s".formatted(actor.value(), held, permitted));
  }
}
