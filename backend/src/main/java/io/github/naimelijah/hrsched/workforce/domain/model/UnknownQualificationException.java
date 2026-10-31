package io.github.naimelijah.hrsched.workforce.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;

/** Raised when no qualification with the given identity exists in the organisation. */
public class UnknownQualificationException extends RuntimeException {

  /**
   * Builds the refusal for an identity this organisation does not define.
   *
   * <p>The message names that identity, so it must not reach a response, which is never told back
   * what it sent (ADR-024). The web adapter answers with a constant sentence instead.
   *
   * @param id the identity that was asked for
   */
  public UnknownQualificationException(QualificationId id) {
    super("No such qualification: %s".formatted(id.value()));
  }
}
